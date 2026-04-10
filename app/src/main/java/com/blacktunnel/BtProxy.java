package com.blacktunnel;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class BtProxy {

    // ── Constantes de conexión ────────────────────────────────────────────────
    private static final String PROXY_IPV6        = "2606:4700::6812:16b7";
    private static final String PROXY_HOST        = "emailmarketing.personal.com.ar";
    private static final int    PROXY_PORT        = 80;
    private static final String TUNNEL_HOST       = "2.brawlpass.com.ar";
    private static final int    SOCKS5_PORT       = 10809; // HEV apunta acá
    private static final int    DNS_PORT          = 53; // DNS local — HEV apunta acá

    // Pool fake DNS de HEV: 198.18.0.0/15
    private static final int    FAKE_IP_START     = (198 << 24) | (18 << 16); // 198.18.0.0
    private static final int    FAKE_IP_MASK      = 0xFFFE0000; // /15

    private static final byte TYPE_OPEN  = 0x01;
    private static final byte TYPE_DATA  = 0x02;
    private static final byte TYPE_CLOSE = 0x03;

    // ── Estado global ─────────────────────────────────────────────────────────
    private static volatile boolean          running;
    private static volatile ServerSocket     socks5Server;
    private static volatile Socket           tunnelSocket;
    private static volatile DataOutputStream tunnelOut;
    private static final Object              tunnelLock    = new Object();
    private static final AtomicInteger       nextStreamId  = new AtomicInteger(1);

    // streamId → socket local HEV
    private static final Map<Integer, Socket>         streams       = new ConcurrentHashMap<>();
    // streamId → latch que se libera en TYPE_CLOSE
    private static final Map<Integer, CountDownLatch> closeLatches  = new ConcurrentHashMap<>();
    // IP fake 198.18.x.x → hostname real (lo llenamos desde el DNS interceptor)
    private static final Map<String, String>          fakeToReal    = new ConcurrentHashMap<>();
    // hostname → IP fake asignada (para no asignar dos veces)
    private static final Map<String, String>          realToFake    = new ConcurrentHashMap<>();
    private static final AtomicInteger                fakeIpCounter = new AtomicInteger(1);

    public interface SocketProtector { boolean protect(Socket s); }

    // ── API pública ───────────────────────────────────────────────────────────

    public static void start(SocketProtector protector) {
        running = true;
        new Thread(() -> connectTunnel(protector), "btproxy-init").start();
    }

    public static void stop() {
        running = false;
        closeLatches.values().forEach(CountDownLatch::countDown);
        closeLatches.clear();
        fakeToReal.clear();
        realToFake.clear();
        try { if (socks5Server  != null) socks5Server.close();  } catch (Exception ignored) {}
        socks5Server  = null;
        streams.values().forEach(s -> { try { s.close(); } catch (Exception ignored) {} });
        streams.clear();
        try { if (tunnelSocket != null) tunnelSocket.close(); } catch (Exception ignored) {}
        tunnelSocket = null;
        tunnelOut    = null;
    }

    // ── Gestión fake DNS ──────────────────────────────────────────────────────

    private static boolean isFakeIp(String ip) {
        try {
            byte[] b = InetAddress.getByName(ip).getAddress();
            if (b.length != 4) return false;
            int addr = ((b[0]&0xFF)<<24)|((b[1]&0xFF)<<16)|((b[2]&0xFF)<<8)|(b[3]&0xFF);
            return (addr & FAKE_IP_MASK) == FAKE_IP_START;
        } catch (Exception e) { return false; }
    }

    private static String allocateFakeIp(String hostname) {
        String existing = realToFake.get(hostname);
        if (existing != null) return existing;
        int n = fakeIpCounter.getAndIncrement() & 0x1FFFF; // 198.18.0.1 .. 198.19.255.254
        if (n == 0) n = 1;
        int addr = FAKE_IP_START | n;
        String fakeIp = ((addr>>24)&0xFF) + "." + ((addr>>16)&0xFF) + "."
                      + ((addr>>8)&0xFF)  + "." + (addr&0xFF);
        fakeToReal.put(fakeIp, hostname);
        realToFake.put(hostname, fakeIp);
        return fakeIp;
    }

    private static String resolveHost(String ip) {
        return fakeToReal.getOrDefault(ip, ip);
    }

    // ── Túnel WebSocket ───────────────────────────────────────────────────────

    private static void connectTunnel(SocketProtector protector) {
        Socket tunnel = openTunnel(protector);
        if (tunnel == null) { SimpleLog.i("openTunnel null"); return; }
        SimpleLog.i("Túnel OK");
        tunnelSocket = tunnel;
        try {
            tunnelOut = new DataOutputStream(tunnel.getOutputStream());
        } catch (Exception e) { SimpleLog.i("tunnelOut: " + e.getMessage()); return; }

        startTunnelReader();
        startKeepalive();
        if (socks5Server == null) {
            startDnsInterceptor();
            startSocks5Server();
        }
        SimpleLog.i("Listo — SOCKS5:" + SOCKS5_PORT + " DNS:" + DNS_PORT);
    }

    private static void startKeepalive() {
        new Thread(() -> {
            while (running && tunnelSocket != null && tunnelSocket.isConnected()) {
                try { Thread.sleep(30_000); } catch (InterruptedException ignored) {}
                try { writeFrame(TYPE_DATA, 0, new byte[0]); } catch (Exception ignored) { break; }
            }
        }, "keepalive").start();
    }

    private static void writeFrame(byte type, int sid, byte[] data) throws Exception {
        synchronized (tunnelLock) {
            if (tunnelOut == null) return;
            tunnelOut.writeByte(type);
            tunnelOut.writeInt(sid);
            tunnelOut.writeInt(data.length);
            if (data.length > 0) tunnelOut.write(data);
            tunnelOut.flush();
        }
    }

    private static void startTunnelReader() {
        new Thread(() -> {
            try {
                DataInputStream inp = new DataInputStream(tunnelSocket.getInputStream());
                while (running) {
                    byte   type = inp.readByte();
                    int    sid  = inp.readInt();
                    int    len  = inp.readInt();
                    byte[] data = len > 0 ? new byte[len] : new byte[0];
                    if (len > 0) inp.readFully(data);

                    if (type == TYPE_DATA) {
                        Socket s = streams.get(sid);
                        if (s != null && !s.isClosed()) {
                            try { s.getOutputStream().write(data); s.getOutputStream().flush(); }
                            catch (Exception ignored) {}
                        }
                    } else if (type == TYPE_CLOSE) {
                        CountDownLatch latch = closeLatches.remove(sid);
                        if (latch != null) latch.countDown();
                        streams.remove(sid);
                    }
                }
            } catch (Exception ignored) {}
        }, "tunnel-reader").start();
    }

    // ── DNS Interceptor ───────────────────────────────────────────────────────
    // Escucha en UDP 10853, responde con IP fake del pool 198.18.x.x
    // HEV debe apuntar su DNS a 127.0.0.1:10853

    private static void startDnsInterceptor() {
        new Thread(() -> {
            try {
                DatagramSocket udp = new DatagramSocket(null);
                udp.setReuseAddress(true);
                udp.bind(new InetSocketAddress("127.0.0.1", DNS_PORT));
                SimpleLog.i("DNS interceptor en :" + DNS_PORT);
                byte[] buf = new byte[512];
                while (running) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    udp.receive(pkt);
                    byte[] query = java.util.Arrays.copyOf(pkt.getData(), pkt.getLength());
                    final DatagramSocket udpFinal = udp;
                    final DatagramPacket pktFinal = pkt;
                    new Thread(() -> handleDnsQuery(udpFinal, pktFinal, query), "dns").start();
                }
            } catch (Exception e) {
                SimpleLog.i("DNS error: " + e.getMessage());
            }
        }, "dns-server").start();
    }

    private static void handleDnsQuery(DatagramSocket udp, DatagramPacket pkt, byte[] query) {
        try {
            String hostname = parseDnsHostname(query);
            if (hostname == null || hostname.isEmpty()) return;

            // Detectar tipo de query: A (1) o AAAA (28)
            int qtype = 1;
            try {
                // QNAME termina en 0x00, luego 2 bytes de tipo
                int pos = 12;
                while (pos < query.length && query[pos] != 0) {
                    pos += (query[pos] & 0xFF) + 1;
                }
                pos++; // saltar el 0x00
                qtype = ((query[pos] & 0xFF) << 8) | (query[pos+1] & 0xFF);
            } catch (Exception ignored) {}

            byte[] response;
            if (qtype == 28) {
                // AAAA — responder NXDOMAIN para forzar IPv4
                response = buildNxDomain(query);
            } else {
                // A — responder con IP fake del pool
                String fakeIp = allocateFakeIp(hostname);
                response = buildDnsResponse(query, hostname, fakeIp);
            }
            udp.send(new DatagramPacket(response, response.length,
                    pkt.getAddress(), pkt.getPort()));
        } catch (Exception ignored) {}
    }

    private static byte[] buildNxDomain(byte[] query) {
        // Respuesta NXDOMAIN: header con RCODE=3, sin answers
        ByteBuffer buf = ByteBuffer.allocate(query.length);
        buf.put(query[0]); buf.put(query[1]); // transaction ID
        buf.put((byte)0x81); buf.put((byte)0x83); // response, NXDOMAIN
        buf.putShort((short)1); // QDCOUNT
        buf.putShort((short)0); // ANCOUNT
        buf.putShort((short)0); // NSCOUNT
        buf.putShort((short)0); // ARCOUNT
        // Copiar question
        buf.put(query, 12, query.length - 12);
        byte[] result = new byte[buf.position()];
        buf.rewind(); buf.get(result);
        return result;
    }

    private static String parseDnsHostname(byte[] data) {
        try {
            // DNS query: 12 bytes header, luego QNAME
            int pos = 12;
            StringBuilder sb = new StringBuilder();
            while (pos < data.length) {
                int len = data[pos++] & 0xFF;
                if (len == 0) break;
                if (sb.length() > 0) sb.append('.');
                sb.append(new String(data, pos, len));
                pos += len;
            }
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    private static byte[] buildDnsResponse(byte[] query, String hostname, String fakeIp) throws Exception {
        byte[] ip = InetAddress.getByName(fakeIp).getAddress();
        ByteBuffer buf = ByteBuffer.allocate(512);

        // Header — copiar ID del query
        buf.put(query[0]); buf.put(query[1]); // transaction ID
        buf.put((byte)0x81); buf.put((byte)0x80); // flags: response, recursion available
        buf.putShort((short)1);  // QDCOUNT
        buf.putShort((short)1);  // ANCOUNT
        buf.putShort((short)0);  // NSCOUNT
        buf.putShort((short)0);  // ARCOUNT

        // Question — copiar del query original
        int qstart = 12;
        int qend = qstart;
        while (qend < query.length && query[qend] != 0) qend++;
        qend += 5; // null byte + type + class
        buf.put(query, qstart, qend - qstart);

        // Answer
        buf.put((byte)0xC0); buf.put((byte)0x0C); // pointer al QNAME
        buf.putShort((short)1);   // TYPE A
        buf.putShort((short)1);   // CLASS IN
        buf.putInt(300);           // TTL 5 min
        buf.putShort((short)4);   // RDLENGTH
        buf.put(ip);               // RDATA

        byte[] result = new byte[buf.position()];
        buf.rewind();
        buf.get(result);
        return result;
    }

    // ── Servidor SOCKS5 local ─────────────────────────────────────────────────

    private static void startSocks5Server() {
        try {
            socks5Server = new ServerSocket(SOCKS5_PORT, 256,
                    InetAddress.getByName("127.0.0.1"));
            SimpleLog.i("SOCKS5 en :" + SOCKS5_PORT);
            new Thread(() -> {
                while (running) {
                    try {
                        Socket client = socks5Server.accept();
                        client.setTcpNoDelay(true);
                        new Thread(() -> handleSocks5(client), "socks5").start();
                    } catch (Exception ignored) { break; }
                }
            }, "socks5-accept").start();
        } catch (Exception e) {
            SimpleLog.i("socks5 bind error: " + e.getMessage());
        }
    }

    private static void handleSocks5(Socket client) {
        int sid = -1;
        CountDownLatch latch = null;
        try {
            InputStream  in  = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // Negociación
            int ver = in.read(); if (ver != 5) { client.close(); return; }
            int nm  = in.read();
            readExact(in, nm);
            out.write(new byte[]{0x05, 0x00}); out.flush();

            // Request
            byte[] req = readExact(in, 4);
            if (req[0] != 5) { client.close(); return; }
            byte cmd = req[1];

            int atyp = req[3] & 0xFF;
            String host;
            int    port;

            if (atyp == 1) {
                byte[] b = readExact(in, 4);
                String ip = (b[0]&0xFF)+"."+(b[1]&0xFF)+"."+(b[2]&0xFF)+"."+(b[3]&0xFF);
                host = resolveHost(ip); // IP fake → hostname real
            } else if (atyp == 3) {
                int l = in.read();
                host = new String(readExact(in, l));
            } else if (atyp == 4) {
                byte[] b = readExact(in, 16);
                String ip6 = InetAddress.getByAddress(b).getHostAddress();
                host = resolveHost(ip6);
            } else {
                out.write(new byte[]{0x05,0x08,0x00,0x01, 0,0,0,0, 0,0}); out.flush();
                client.close(); return;
            }
            byte[] pb = readExact(in, 2);
            port = ((pb[0]&0xFF)<<8)|(pb[1]&0xFF);

            if (cmd == 1) {
                // CONNECT
                out.write(new byte[]{0x05,0x00,0x00,0x01, 0,0,0,0, 0,0}); out.flush();

                sid   = nextStreamId.getAndIncrement();
                latch = new CountDownLatch(1);
                streams.put(sid, client);
                closeLatches.put(sid, latch);

                writeFrame(TYPE_OPEN, sid, (host + ":" + port + "\n").getBytes());

                // Upload loop
                byte[] buf = new byte[32768];
                try {
                    while (running) {
                        int n = in.read(buf);
                        if (n < 0) break;
                        byte[] payload = new byte[n];
                        System.arraycopy(buf, 0, payload, 0, n);
                        writeFrame(TYPE_DATA, sid, payload);
                    }
                } catch (Exception ignored) {}

                try { writeFrame(TYPE_CLOSE, sid, new byte[0]); } catch (Exception ignored) {}
                latch.await(20, TimeUnit.SECONDS);

            } else if (cmd == 3) {
                // UDP ASSOCIATE — para DNS y UDP
                // El cliente manda datagramas que HEV envuelve en SOCKS5 UDP
                out.write(new byte[]{0x05,0x00,0x00,0x01,
                        127,0,0,1,
                        (byte)(SOCKS5_PORT>>8),(byte)(SOCKS5_PORT&0xFF)});
                out.flush();
                // Mantener la conexión de control abierta
                try { while (in.read() >= 0) {} } catch (Exception ignored) {}
            } else {
                out.write(new byte[]{0x05,0x07,0x00,0x01, 0,0,0,0, 0,0}); out.flush();
            }

        } catch (Exception e) {
            SimpleLog.i("socks5 err: " + e.getMessage());
        } finally {
            if (sid >= 0) { streams.remove(sid); if (latch != null) closeLatches.remove(sid); }
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private static byte[] readExact(InputStream in, int n) throws Exception {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new Exception("EOF");
            off += r;
        }
        return buf;
    }

    // ── Apertura del túnel ────────────────────────────────────────────────────

    private static Socket openTunnel(SocketProtector protector) {
        try {
            Socket s = openProxySocket(protector);
            if (s == null) return null;
            s.setTcpNoDelay(true);

            OutputStream out = s.getOutputStream();
            InputStream  inp = s.getInputStream();

            out.write(("GET / HTTP/1.1\r\nHost: " + PROXY_HOST + "\r\n\r\n").getBytes());
            out.flush();
            SimpleLog.i("P1 enviado");
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            out.write(("- / HTTP/1.1\r\nHost: " + TUNNEL_HOST + "\r\n"
                     + "Upgrade: websocket\r\nAction: tunnel\r\n\r\n").getBytes());
            out.flush();
            SimpleLog.i("P2 enviado");

            s.setSoTimeout(8000);
            StringBuilder raw = new StringBuilder();
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    byte[] tmp = new byte[4096];
                    int n = inp.read(tmp);
                    if (n < 0) break;
                    raw.append(new String(tmp, 0, n));
                    int cnt = 0, idx = 0;
                    while ((idx = raw.indexOf("\r\n\r\n", idx)) >= 0) { cnt++; idx += 4; }
                    if (cnt >= 2) break;
                } catch (java.net.SocketTimeoutException ignored) { break; }
            }
            SimpleLog.i("Resp: " + raw.toString().replace("\r\n","|")
                    .substring(0, Math.min(raw.length(), 100)));
            if (raw.indexOf("HTTP/1.1 101") < 0) {
                SimpleLog.i("Sin 101"); s.close(); return null;
            }
            SimpleLog.i("101 OK");
            s.setSoTimeout(0);
            return s;
        } catch (Exception e) {
            SimpleLog.i("openTunnel: " + e.getMessage()); return null;
        }
    }

    private static Socket openProxySocket(SocketProtector protector) {
        SimpleLog.i("Conectando IPv6...");
        try {
            Socket s = new Socket();
            boolean ok = protector.protect(s);
            SimpleLog.i("protect=" + ok);
            s.setKeepAlive(true); s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(
                    InetAddress.getByName(PROXY_IPV6), PROXY_PORT), 10000);
            SimpleLog.i("IPv6 OK");
            return s;
        } catch (Exception e) { SimpleLog.i("IPv6 fallo: " + e.getMessage()); }
        try {
            for (InetAddress addr : InetAddress.getAllByName(PROXY_HOST)) {
                try {
                    Socket s = new Socket();
                    protector.protect(s);
                    s.setKeepAlive(true); s.setTcpNoDelay(true);
                    s.connect(new InetSocketAddress(addr, PROXY_PORT), 10000);
                    SimpleLog.i("DNS OK: " + addr);
                    return s;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }
}
