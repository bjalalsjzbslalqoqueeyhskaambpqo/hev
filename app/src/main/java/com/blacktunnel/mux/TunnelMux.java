package com.blacktunnel.mux;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class TunnelMux {

    private final String serverHost;
    private final int    serverPort;
    private final String wsPath;
    private final int socks5Port;
    private final int dnsPort;

    private static final int STREAM_TIMEOUT = 120_000;
    private static final int CONN_TIMEOUT   = 15_000;
    private static final int RECONNECT_MS   = 4_000;
    private static final int TCP_BUF        = 32 * 1024;
    private static final int WRITE_BUF      = 8 * 1024;
    private static final int QUEUE_TCP      = 256;
    private static final int QUEUE_UDP      = 8;
    private static final int DNS_TTL_MS     = 60_000;
    private static final int NOP_INTERVAL   = 25_000;

    private static final byte VER      = 0x01;
    private static final byte CMD_SYN  = 0x00;
    private static final byte CMD_FIN  = 0x01;
    private static final byte CMD_PSH  = 0x02;
    private static final byte CMD_NOP  = 0x03;

    private static final byte PROTO_TCP    = 0x01;
    private static final byte PROTO_UDP    = 0x02;
    private static final byte ATYP_IPV4   = 0x01;
    private static final byte ATYP_DOMAIN = 0x03;
    private static final byte ATYP_IPV6   = 0x04;

    private volatile InputStream  tunnelIn;
    private volatile OutputStream tunnelOut;
    private volatile boolean      alive      = false;
    private volatile Socket       tunnelSock;
    private volatile ServerSocket socksServer;
    private volatile DatagramSocket dnsSocket;
    private final Object writeLock = new Object();

    private final ConcurrentHashMap<Integer, Stream>  streams  = new ConcurrentHashMap<>();
    private final AtomicInteger                       nextId   = new AtomicInteger(1);
    private final ConcurrentHashMap<String, DnsEntry> dnsCache = new ConcurrentHashMap<>();

    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    public TunnelMux(String serverHost, int serverPort, String wsPath, int socks5Port, int dnsPort) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.wsPath = wsPath;
        this.socks5Port = socks5Port;
        this.dnsPort = dnsPort;
    }

    private static final class Stream {
        final BlockingQueue<byte[]> queue;
        volatile boolean closed = false;

        Stream(boolean udp) {
            this.queue = new LinkedBlockingQueue<>(udp ? QUEUE_UDP : QUEUE_TCP);
        }

        void push(byte[] data) {
            if (closed) return;
            if (!queue.offer(data)) {
                closed = true;
                queue.offer(new byte[0]);
            }
        }

        void kill() {
            closed = true;
            queue.offer(new byte[0]);
        }

        byte[] poll(long ms) throws InterruptedException {
            return queue.poll(ms, TimeUnit.MILLISECONDS);
        }
    }

    private static final class DnsEntry {
        final byte[] response;
        final long   expires;
        DnsEntry(byte[] r) {
            this.response = r;
            this.expires  = System.currentTimeMillis() + DNS_TTL_MS;
        }
        boolean valid() { return System.currentTimeMillis() < expires; }
    }

    public void start() throws IOException {
        connectAndWatch();
        startSocks5();
        startDns();
    }

    public void stop() {
        alive = false;
        teardown();
        try { if (socksServer != null) socksServer.close(); } catch (IOException ignored) {}
        try { if (dnsSocket != null) dnsSocket.close(); } catch (Exception ignored) {}
        pool.shutdownNow();
    }

    private void connectAndWatch() throws IOException {
        connect();
        pool.execute(() -> {
            while (!pool.isShutdown()) {
                try { Thread.sleep(RECONNECT_MS); } catch (InterruptedException ignored) {}
                if (!alive && !pool.isShutdown()) {
                    try { connect(); }
                    catch (IOException ignored) {}
                }
            }
        });
    }

    private void connect() throws IOException {
        Socket sock = new Socket();
        sock.connect(new InetSocketAddress(serverHost, serverPort), CONN_TIMEOUT);
        sock.setTcpNoDelay(true);
        sock.setKeepAlive(true);
        sock.setSoTimeout(0);

        OutputStream rawOut = sock.getOutputStream();
        InputStream  rawIn  = sock.getInputStream();

        byte[] keyBytes = new byte[16];
        new Random().nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);

        String req = "GET " + wsPath + " HTTP/1.1\r\n"
            + "Host: " + serverHost + "\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: " + key + "\r\n"
            + "Sec-WebSocket-Version: 13\r\n\r\n";
        rawOut.write(req.getBytes());
        rawOut.flush();

        StringBuilder hdr = new StringBuilder();
        int prev = -1;
        while (true) {
            int c = rawIn.read();
            if (c == -1) throw new IOException("conexión cerrada durante handshake");
            hdr.append((char) c);
            if (prev == '\n' && c == '\n' && hdr.toString().contains("\r\n\r\n")) break;
            prev = c;
        }
        if (!hdr.toString().contains("101")) throw new IOException("handshake WS rechazado");

        tunnelSock = sock;
        tunnelIn   = new BufferedInputStream(rawIn, TCP_BUF);
        tunnelOut  = new BufferedOutputStream(rawOut, WRITE_BUF);
        alive      = true;

        startReadLoop();
        startNopLoop();
    }

    private void startReadLoop() {
        pool.execute(() -> {
            try {
                DataInputStream din = new DataInputStream(tunnelIn);
                while (alive) {
                    int sid = din.readInt();
                    din.readByte();
                    byte cmd = din.readByte();
                    int plen = din.readUnsignedShort();
                    byte[] payload = new byte[plen];
                    if (plen > 0) din.readFully(payload);

                    if (cmd == CMD_NOP) continue;

                    Stream s = streams.get(sid);
                    if (s == null) {
                        if (cmd != CMD_FIN) {
                            try { writeFrame(sid, CMD_FIN, null); } catch (IOException ignored) {}
                        }
                        continue;
                    }

                    if (cmd == CMD_PSH) {
                        s.push(payload);
                    } else if (cmd == CMD_FIN) {
                        s.kill();
                        streams.remove(sid);
                    }
                }
            } catch (IOException ignored) {
            } finally {
                teardown();
            }
        });
    }

    private void startNopLoop() {
        pool.execute(() -> {
            while (alive) {
                try {
                    Thread.sleep(NOP_INTERVAL);
                    if (alive) sendFrame(0, CMD_NOP, null);
                } catch (InterruptedException ignored) {
                } catch (IOException e) {
                    teardown();
                    return;
                }
            }
        });
    }

    private void teardown() {
        alive = false;
        streams.values().forEach(Stream::kill);
        streams.clear();
        try { if (tunnelSock != null) tunnelSock.close(); } catch (IOException ignored) {}
    }

    private void sendFrame(int sid, byte cmd, byte[] payload) throws IOException {
        synchronized (writeLock) { writeFrame(sid, cmd, payload); }
    }

    private void writeFrame(int sid, byte cmd, byte[] payload) throws IOException {
        int plen = payload != null ? payload.length : 0;
        byte[] hdr = new byte[8];
        hdr[0] = (byte)(sid >> 24); hdr[1] = (byte)(sid >> 16);
        hdr[2] = (byte)(sid >> 8);  hdr[3] = (byte) sid;
        hdr[4] = VER;
        hdr[5] = cmd;
        hdr[6] = (byte)(plen >> 8); hdr[7] = (byte) plen;
        tunnelOut.write(hdr);
        if (plen > 0) tunnelOut.write(payload);
        tunnelOut.flush();
    }

    private int newStream(boolean udp) {
        for (int attempts = 0; attempts < 2000; attempts++) {
            int sid = nextId.getAndAdd(2);
            if (sid <= 0 || sid > 0x7FFFFFFE) {
                nextId.compareAndSet(sid + 2, 1);
                continue;
            }
            if (streams.putIfAbsent(sid, new Stream(udp)) == null) return sid;
        }
        throw new IllegalStateException("sin IDs disponibles");
    }

    private void finStream(int sid) {
        Stream s = streams.remove(sid);
        if (s != null) s.kill();
        try { sendFrame(sid, CMD_FIN, null); } catch (IOException ignored) {}
    }

    private byte[] synPayload(byte proto, byte atyp, byte[] addr, int port) {
        ByteBuffer b = ByteBuffer.allocate(1 + 1 + addr.length + 2);
        b.put(proto); b.put(atyp); b.put(addr);
        b.putShort((short) port);
        return b.array();
    }

    private void startSocks5() throws IOException {
        ServerSocket srv = new ServerSocket(socks5Port, 512, InetAddress.getLoopbackAddress());
        socksServer = srv;
        pool.execute(() -> {
            while (!srv.isClosed()) {
                try { Socket c = srv.accept(); pool.execute(() -> handleSocks5(c)); }
                catch (IOException e) { break; }
            }
        });
    }

    private void handleSocks5(Socket conn) {
        int sid = -1;
        try {
            conn.setTcpNoDelay(true);
            conn.setSoTimeout(CONN_TIMEOUT);
            DataInputStream in = new DataInputStream(conn.getInputStream());
            OutputStream out = conn.getOutputStream();

            if (in.read() != 5) { conn.close(); return; }
            in.skipBytes(in.read());
            out.write(new byte[]{0x05, 0x00});
            out.flush();

            in.readByte();
            byte sCmd = in.readByte();
            in.readByte();
            byte atyp = in.readByte();

            if (sCmd != 0x01) {
                out.write(new byte[]{0x05, 0x07, 0x00, 0x01, 0,0,0,0, 0,0});
                out.flush(); conn.close(); return;
            }

            byte[] addr;
            if (atyp == ATYP_IPV4) {
                addr = new byte[4]; in.readFully(addr);
            } else if (atyp == ATYP_DOMAIN) {
                int len = in.read() & 0xFF;
                byte[] dom = new byte[len]; in.readFully(dom);
                addr = new byte[1 + len];
                addr[0] = (byte) len;
                System.arraycopy(dom, 0, addr, 1, len);
            } else if (atyp == ATYP_IPV6) {
                addr = new byte[16]; in.readFully(addr);
            } else {
                out.write(new byte[]{0x05, 0x08, 0x00, 0x01, 0,0,0,0, 0,0});
                out.flush(); conn.close(); return;
            }

            int port = in.readUnsignedShort();

            out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0,0,0,0, 0,0});
            out.flush();
            conn.setSoTimeout(0);

            if (!alive) { conn.close(); return; }

            sid = newStream(false);
            final int streamId = sid;
            final Stream s = streams.get(sid);
            if (s == null) { conn.close(); return; }

            sendFrame(sid, CMD_SYN, synPayload(PROTO_TCP, atyp, addr, port));

            pool.execute(() -> {
                try {
                    byte[] buf = new byte[TCP_BUF]; int n;
                    InputStream appIn = conn.getInputStream();
                    while (!s.closed && (n = appIn.read(buf)) != -1)
                        sendFrame(streamId, CMD_PSH, Arrays.copyOf(buf, n));
                } catch (IOException ignored) {
                } finally { finStream(streamId); }
            });

            byte[] data;
            while ((data = s.poll(STREAM_TIMEOUT)) != null) {
                if (data.length == 0) break;
                out.write(data);
                out.flush();
            }

        } catch (Exception ignored) {
        } finally {
            if (sid > 0) finStream(sid);
            try { conn.close(); } catch (IOException ignored) {}
        }
    }

    private void startDns() throws SocketException {
        DatagramSocket sock = new DatagramSocket(dnsPort, InetAddress.getLoopbackAddress());
        dnsSocket = sock;
        pool.execute(() -> {
            byte[] buf = new byte[4096];
            while (!sock.isClosed()) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    sock.receive(pkt);
                    byte[] query = Arrays.copyOf(pkt.getData(), pkt.getLength());
                    InetAddress src = pkt.getAddress();
                    int srcPort = pkt.getPort();
                    pool.execute(() -> handleDns(sock, query, src, srcPort));
                } catch (IOException e) { break; }
            }
        });
    }

    private void handleDns(DatagramSocket sock, byte[] query, InetAddress src, int srcPort) {
        if (query.length < 12) return;
        String key = Base64.getEncoder().encodeToString(Arrays.copyOfRange(query, 2, query.length));

        DnsEntry cached = dnsCache.get(key);
        if (cached != null && cached.valid()) {
            byte[] resp = Arrays.copyOf(cached.response, cached.response.length);
            resp[0] = query[0]; resp[1] = query[1];
            udpSend(sock, resp, src, srcPort);
            return;
        }
        dnsCache.remove(key);

        byte[] syn = synPayload(PROTO_UDP, ATYP_IPV4, new byte[]{8,8,8,8}, 53);
        byte[] resp = udpRoundTrip(syn, query, 8_000);
        if (resp != null && resp.length >= 2) {
            dnsCache.put(key, new DnsEntry(resp));
            resp[0] = query[0]; resp[1] = query[1];
            udpSend(sock, resp, src, srcPort);
        }
    }

    private byte[] udpRoundTrip(byte[] synPayload, byte[] data, int timeoutMs) {
        int sid = -1;
        try {
            if (!alive) return null;
            sid = newStream(true);
            sendFrame(sid, CMD_SYN, synPayload);
            sendFrame(sid, CMD_PSH, data);
            Stream s = streams.get(sid);
            if (s == null) return null;
            byte[] resp = s.poll(timeoutMs);
            return (resp != null && resp.length > 0) ? resp : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (sid > 0) finStream(sid);
        }
    }

    private void udpSend(DatagramSocket sock, byte[] data, InetAddress addr, int port) {
        try { sock.send(new DatagramPacket(data, data.length, addr, port)); }
        catch (IOException ignored) {}
    }
}
