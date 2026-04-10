package com.blacktunnel;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class BtProxy {

    private static final String PROXY_IPV6  = "2606:4700::6812:16b7";
    private static final String PROXY_HOST  = "emailmarketing.personal.com.ar";
    private static final int    PROXY_PORT  = 80;
    private static final String TUNNEL_HOST = "2.brawlpass.com.ar";
    private static final int    SOCKS5_PORT = 10809;

    private static final byte TYPE_OPEN  = 0x01;
    private static final byte TYPE_DATA  = 0x02;
    private static final byte TYPE_CLOSE = 0x03;

    private static volatile boolean          running;
    private static volatile ServerSocket     socks5Server;
    private static volatile Socket           tunnelSocket;
    private static volatile DataOutputStream tunnelOut;
    private static final Object              tunnelLock    = new Object();
    private static final AtomicInteger       nextStreamId  = new AtomicInteger(1);
    private static final Map<Integer, Socket>         streams      = new ConcurrentHashMap<>();
    private static final Map<Integer, CountDownLatch> closeLatches = new ConcurrentHashMap<>();

    public interface SocketProtector { boolean protect(Socket s); }

    public static void start(SocketProtector protector) {
        running = true;
        nextStreamId.set(1);
        new Thread(() -> connectTunnel(protector), "btproxy-init").start();
    }

    public static void stop() {
        running = false;
        closeLatches.values().forEach(CountDownLatch::countDown);
        closeLatches.clear();
        try { if (socks5Server != null) socks5Server.close(); } catch (Exception ignored) {}
        socks5Server = null;
        streams.values().forEach(s -> { try { s.close(); } catch (Exception ignored) {} });
        streams.clear();
        try { if (tunnelSocket != null) tunnelSocket.close(); } catch (Exception ignored) {}
        tunnelSocket = null;
        tunnelOut    = null;
    }

    private static void connectTunnel(SocketProtector protector) {
        Socket t = openTunnel(protector);
        if (t == null) { SimpleLog.i("Túnel null"); return; }
        tunnelSocket = t;
        try { tunnelOut = new DataOutputStream(t.getOutputStream()); }
        catch (Exception e) { SimpleLog.i("tunnelOut: " + e.getMessage()); return; }
        startTunnelReader();
        startKeepalive();
        if (socks5Server == null) startSocks5Relay();
        SimpleLog.i("Listo — relay SOCKS5 :" + SOCKS5_PORT);
    }

    private static void startKeepalive() {
        new Thread(() -> {
            while (running && tunnelSocket != null && tunnelSocket.isConnected()) {
                try { Thread.sleep(30_000); } catch (InterruptedException ignored) {}
                try { writeFrame(TYPE_DATA, 0, new byte[0]); }
                catch (Exception ignored) { break; }
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
                            try {
                                s.getOutputStream().write(data);
                                s.getOutputStream().flush();
                            } catch (Exception ignored) {}
                        }
                    } else if (type == TYPE_CLOSE) {
                        CountDownLatch l = closeLatches.remove(sid);
                        if (l != null) l.countDown();
                        streams.remove(sid);
                    }
                }
            } catch (Exception ignored) {}
        }, "tunnel-reader").start();
    }

    private static void startSocks5Relay() {
        try {
            socks5Server = new ServerSocket(SOCKS5_PORT, 512,
                    InetAddress.getByName("127.0.0.1"));
            SimpleLog.i("Relay SOCKS5 :" + SOCKS5_PORT);
            new Thread(() -> {
                while (running) {
                    try {
                        Socket client = socks5Server.accept();
                        client.setTcpNoDelay(true);
                        new Thread(() -> relayStream(client), "relay").start();
                    } catch (Exception ignored) { break; }
                }
            }, "relay-accept").start();
        } catch (Exception e) { SimpleLog.i("relay bind: " + e.getMessage()); }
    }

    private static void relayStream(Socket client) {
        int sid = -1;
        CountDownLatch latch = null;
        try {
            sid   = nextStreamId.getAndIncrement();
            latch = new CountDownLatch(1);
            streams.put(sid, client);
            closeLatches.put(sid, latch);

            writeFrame(TYPE_OPEN, sid, new byte[0]);

            byte[] buf = new byte[32768];
            try {
                while (running) {
                    int n = client.getInputStream().read(buf);
                    if (n < 0) break;
                    byte[] payload = new byte[n];
                    System.arraycopy(buf, 0, payload, 0, n);
                    writeFrame(TYPE_DATA, sid, payload);
                }
            } catch (Exception ignored) {}

            try { writeFrame(TYPE_CLOSE, sid, new byte[0]); } catch (Exception ignored) {}
            latch.await(15, TimeUnit.SECONDS);

        } catch (Exception e) {
            SimpleLog.i("relay err: " + e.getMessage());
        } finally {
            if (sid >= 0) {
                streams.remove(sid);
                if (latch != null) closeLatches.remove(sid);
            }
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private static Socket openTunnel(SocketProtector protector) {
        try {
            Socket s = openProxy(protector);
            if (s == null) return null;
            s.setTcpNoDelay(true);
            OutputStream out = s.getOutputStream();
            InputStream  inp = s.getInputStream();

            out.write(("GET / HTTP/1.1\r\nHost: " + PROXY_HOST + "\r\n\r\n").getBytes());
            out.flush(); SimpleLog.i("P1");
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            out.write(("- / HTTP/1.1\r\nHost: " + TUNNEL_HOST
                     + "\r\nUpgrade: websocket\r\nAction: tunnel\r\n\r\n").getBytes());
            out.flush(); SimpleLog.i("P2");

            s.setSoTimeout(8000);
            StringBuilder raw = new StringBuilder();
            long dl = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < dl) {
                try {
                    byte[] tmp = new byte[4096]; int n = inp.read(tmp);
                    if (n < 0) break;
                    raw.append(new String(tmp, 0, n));
                    int cnt = 0, idx = 0;
                    while ((idx = raw.indexOf("\r\n\r\n", idx)) >= 0) { cnt++; idx += 4; }
                    if (cnt >= 2) break;
                } catch (java.net.SocketTimeoutException ignored) { break; }
            }
            if (!raw.toString().contains("101")) {
                SimpleLog.i("Sin 101"); s.close(); return null;
            }
            SimpleLog.i("101 OK");
            s.setSoTimeout(0);
            return s;
        } catch (Exception e) { SimpleLog.i("openTunnel: " + e.getMessage()); return null; }
    }

    private static Socket openProxy(SocketProtector protector) {
        SimpleLog.i("Conectando...");
        try {
            Socket s = new Socket();
            if (!protector.protect(s)) {
                try { s.close(); } catch (Exception ignored) {}
                SimpleLog.i("protect falló IPv6");
                return null;
            }
            s.setKeepAlive(true); s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(
                    InetAddress.getByName(PROXY_IPV6), PROXY_PORT), 10000);
            SimpleLog.i("IPv6 OK"); return s;
        } catch (Exception e) { SimpleLog.i("IPv6: " + e.getMessage()); }
        try {
            for (InetAddress a : InetAddress.getAllByName(PROXY_HOST)) {
                try {
                    Socket s = new Socket();
                    if (!protector.protect(s)) {
                        try { s.close(); } catch (Exception ignored) {}
                        SimpleLog.i("protect falló DNS");
                        return null;
                    }
                    s.setKeepAlive(true); s.setTcpNoDelay(true);
                    s.connect(new InetSocketAddress(a, PROXY_PORT), 10000);
                    SimpleLog.i("DNS OK"); return s;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }
}
