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
import java.util.concurrent.atomic.AtomicInteger;

public final class BtProxy {
    private static final String PROXY_IPV6       = "2606:4700::6812:16b7";
    private static final String PROXY_HOST       = "emailmarketing.personal.com.ar";
    private static final int    PROXY_PORT       = 80;
    private static final String TUNNEL_HOST      = "2.brawlpass.com.ar";
    private static final int    TUNNEL_LOCAL_PORT = 10809;

    private static final byte TYPE_OPEN  = 0x01;
    private static final byte TYPE_DATA  = 0x02;
    private static final byte TYPE_CLOSE = 0x03;

    private static volatile boolean          running;
    private static volatile ServerSocket     bridgeServer;
    private static volatile Socket           tunnelSocket;
    private static volatile DataOutputStream tunnelOut;
    private static final Object              tunnelLock   = new Object();
    private static final AtomicInteger       nextStreamId = new AtomicInteger(1);
    private static final Map<Integer, Socket> streams     = new ConcurrentHashMap<>();

    public interface SocketProtector { boolean protect(Socket s); }

    // ── API pública ───────────────────────────────────────────────────────────

    public static void start(SocketProtector protector) {
        running = true;
        new Thread(() -> connectTunnel(protector), "btproxy-init").start();
    }

    public static void stop() {
        running = false;
        try { if (bridgeServer != null) bridgeServer.close(); } catch (Exception ignored) {}
        bridgeServer = null;
        for (Socket s : streams.values()) { try { s.close(); } catch (Exception ignored) {} }
        streams.clear();
        try { if (tunnelSocket != null) tunnelSocket.close(); } catch (Exception ignored) {}
        tunnelSocket = null;
        tunnelOut    = null;
    }

    // ── Túnel WebSocket fake ──────────────────────────────────────────────────

    private static void connectTunnel(SocketProtector protector) {
        Socket tunnel = openTunnel(protector);
        if (tunnel == null) { SimpleLog.i("openTunnel devolvió null"); return; }
        SimpleLog.i("Túnel establecido");
        tunnelSocket = tunnel;
        try {
            tunnelOut = new DataOutputStream(tunnel.getOutputStream());
        } catch (Exception e) {
            SimpleLog.i("Error tunnelOut: " + e.getMessage());
            return;
        }
        startTunnelReader();
        startKeepalive();
        if (bridgeServer == null) startBridge(protector);
        SimpleLog.i("Bridge SOCKS5 local listo en :" + TUNNEL_LOCAL_PORT);
    }

    private static void startKeepalive() {
        new Thread(() -> {
            while (running && tunnelSocket != null && tunnelSocket.isConnected()) {
                try { Thread.sleep(45_000); } catch (InterruptedException ignored) {}
                try { writeFrame(TYPE_DATA, 0, new byte[0]); } catch (Exception ignored) { break; }
            }
        }, "tunnel-keepalive").start();
    }

    private static void writeFrame(byte type, int streamId, byte[] data) throws Exception {
        synchronized (tunnelLock) {
            if (tunnelOut == null) return;
            tunnelOut.writeByte(type);
            tunnelOut.writeInt(streamId);
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
                    byte  type     = inp.readByte();
                    int   streamId = inp.readInt();
                    int   length   = inp.readInt();
                    byte[] data    = length > 0 ? new byte[length] : new byte[0];
                    if (length > 0) inp.readFully(data);

                    if (type == TYPE_DATA) {
                        Socket client = streams.get(streamId);
                        if (client != null) {
                            try { client.getOutputStream().write(data); client.getOutputStream().flush(); }
                            catch (Exception ignored) {}
                        }
                    } else if (type == TYPE_CLOSE) {
                        Socket client = streams.remove(streamId);
                        if (client != null) try { client.close(); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }, "tunnel-reader").start();
    }

    // ── Bridge SOCKS5 local ───────────────────────────────────────────────────
    // Acepta conexiones de HEV, resuelve el handshake SOCKS5 localmente,
    // y solo envía el tráfico real (post-handshake) etiquetado por el túnel smux.

    private static void startBridge(SocketProtector protector) {
        try {
            ServerSocket server = new ServerSocket(TUNNEL_LOCAL_PORT, 256,
                    InetAddress.getByName("127.0.0.1"));
            bridgeServer = server;
            SimpleLog.i("Bridge escuchando en 127.0.0.1:" + TUNNEL_LOCAL_PORT);
            new Thread(() -> {
                while (running) {
                    try {
                        Socket client = server.accept();
                        client.setTcpNoDelay(true);
                        new Thread(() -> handleSocks5Client(client), "socks5-handler").start();
                    } catch (Exception ignored) { break; }
                }
            }, "bridge-accept").start();
        } catch (Exception e) {
            SimpleLog.i("Error bridge: " + e.getMessage());
        }
    }

    // Handshake SOCKS5 completo localmente, luego abre stream smux al servidor
    private static void handleSocks5Client(Socket client) {
        try {
            InputStream  cin  = client.getInputStream();
            OutputStream cout = client.getOutputStream();

            // ── Fase 1: negociación de método ─────────────────────────────────
            int ver = cin.read();
            if (ver != 5) { client.close(); return; }
            int nMethods = cin.read();
            byte[] methods = readExact(cin, nMethods);
            // Aceptar sin autenticación (0x00)
            cout.write(new byte[]{0x05, 0x00});
            cout.flush();

            // ── Fase 2: request ───────────────────────────────────────────────
            byte[] req = readExact(cin, 4);
            if (req[0] != 5 || req[1] != 1) { // solo CONNECT
                cout.write(new byte[]{0x05, 0x07, 0x00, 0x01, 0,0,0,0, 0,0});
                cout.flush();
                client.close();
                return;
            }
            int atyp = req[3] & 0xFF;
            String host;
            if (atyp == 1) {
                byte[] ip4 = readExact(cin, 4);
                host = (ip4[0]&0xFF)+"."+(ip4[1]&0xFF)+"."+(ip4[2]&0xFF)+"."+(ip4[3]&0xFF);
            } else if (atyp == 3) {
                int len = cin.read();
                host = new String(readExact(cin, len));
            } else if (atyp == 4) {
                byte[] ip6 = readExact(cin, 16);
                host = "[" + java.net.InetAddress.getByAddress(ip6).getHostAddress() + "]";
            } else {
                cout.write(new byte[]{0x05, 0x08, 0x00, 0x01, 0,0,0,0, 0,0});
                cout.flush();
                client.close();
                return;
            }
            byte[] portBytes = readExact(cin, 2);
            int port = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);

            SimpleLog.i("CONNECT " + host + ":" + port);

            // ── Responder éxito al cliente (HEV) ─────────────────────────────
            // Indicamos éxito con IP 0.0.0.0:0 — el relay real lo hace el servidor
            cout.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0,0,0,0, 0,0});
            cout.flush();

            // ── Abrir stream smux y enviar destino al servidor ─────────────────
            int streamId = nextStreamId.getAndIncrement();
            streams.put(streamId, client);

            // El primer DATA frame lleva el destino codificado: "host:port\n" + datos reales
            // Usamos un mini-protocolo: TYPE_OPEN lleva el destino como payload
            byte[] dest = (host + ":" + port + "\n").getBytes();
            writeFrame(TYPE_OPEN, streamId, dest);

            // ── Relay: cliente → servidor ─────────────────────────────────────
            byte[] buf = new byte[65536];
            try {
                while (running) {
                    int n = cin.read(buf);
                    if (n < 0) break;
                    byte[] payload = new byte[n];
                    System.arraycopy(buf, 0, payload, 0, n);
                    writeFrame(TYPE_DATA, streamId, payload);
                }
            } catch (Exception ignored) {}

            writeFrame(TYPE_CLOSE, streamId, new byte[0]);
            streams.remove(streamId);
            client.close();

        } catch (Exception e) {
            SimpleLog.i("socks5 error: " + e.getMessage());
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

    // ── Apertura del túnel WebSocket fake ────────────────────────────────────

    private static Socket openTunnel(SocketProtector protector) {
        try {
            Socket socket = openProxySocket(protector);
            if (socket == null) return null;
            socket.setTcpNoDelay(true);

            OutputStream out = socket.getOutputStream();
            InputStream  inp = socket.getInputStream();

            String p1 = "GET / HTTP/1.1\r\nHost: " + PROXY_HOST + "\r\n\r\n";
            String p2 = "- / HTTP/1.1\r\nHost: " + TUNNEL_HOST + "\r\n"
                      + "Upgrade: websocket\r\nAction: tunnel\r\n\r\n";

            out.write(p1.getBytes()); out.flush();
            SimpleLog.i("P1 enviado");
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            out.write(p2.getBytes()); out.flush();
            SimpleLog.i("P2 enviado");

            socket.setSoTimeout(8000);
            StringBuilder raw = new StringBuilder();
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    byte[] tmp = new byte[4096];
                    int n = inp.read(tmp);
                    if (n < 0) break;
                    raw.append(new String(tmp, 0, n));
                    int count = 0, idx = 0;
                    while ((idx = raw.indexOf("\r\n\r\n", idx)) >= 0) { count++; idx += 4; }
                    if (count >= 2) break;
                } catch (java.net.SocketTimeoutException ignored) { break; }
            }

            SimpleLog.i("Respuesta: " + raw.toString().replace("\r\n", "|")
                    .substring(0, Math.min(raw.length(), 200)));

            if (raw.indexOf("HTTP/1.1 101") < 0) {
                SimpleLog.i("Sin 101");
                socket.close();
                return null;
            }
            SimpleLog.i("Handshake 101 OK");
            socket.setSoTimeout(0);
            return socket;
        } catch (Exception e) {
            SimpleLog.i("openTunnel: " + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        }
    }

    private static Socket openProxySocket(SocketProtector protector) {
        SimpleLog.i("Conectando IPv6: " + PROXY_IPV6);
        try {
            Socket s = new Socket();
            boolean ok = protector.protect(s);
            SimpleLog.i("protect=" + ok);
            s.setKeepAlive(true);
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(InetAddress.getByName(PROXY_IPV6), PROXY_PORT), 10000);
            SimpleLog.i("Socket IPv6 OK");
            return s;
        } catch (Exception e) { SimpleLog.i("IPv6 falló: " + e.getMessage()); }
        try {
            for (InetAddress addr : InetAddress.getAllByName(PROXY_HOST)) {
                try {
                    Socket s = new Socket();
                    protector.protect(s);
                    s.setKeepAlive(true);
                    s.setTcpNoDelay(true);
                    s.connect(new InetSocketAddress(addr, PROXY_PORT), 10000);
                    SimpleLog.i("Socket DNS OK: " + addr);
                    return s;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }
}
