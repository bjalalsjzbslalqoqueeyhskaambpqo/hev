package com.blacktunnel;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class BtProxy {
    private static final String PROXY_IPV6 = "2606:4700::6812:16b7";
    private static final String PROXY_HOST = "emailmarketing.personal.com.ar";
    private static final int PROXY_PORT = 80;
    private static final String TUNNEL_HOST = "2.brawlpass.com.ar";
    private static final int TUNNEL_LOCAL_PORT = 10809;

    private static final byte TYPE_OPEN  = 0x01;
    private static final byte TYPE_DATA  = 0x02;
    private static final byte TYPE_CLOSE = 0x03;

    private static volatile boolean running;
    private static volatile ServerSocket bridgeServer;
    private static volatile Socket tunnelSocket;
    private static volatile DataOutputStream tunnelOut;
    private static final Object tunnelLock = new Object();
    private static final AtomicInteger nextStreamId = new AtomicInteger(1);
    private static final Map<Integer, Socket> streams = new ConcurrentHashMap<>();

    public interface SocketProtector { void protect(Socket s); }

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
        tunnelOut = null;
    }

    private static void connectTunnel(SocketProtector protector) {
        Socket tunnel = openTunnel(protector);
        if (tunnel == null) { SimpleLog.i("openTunnel devolvió null"); return; }
        SimpleLog.i("Túnel establecido, iniciando bridge y reader");
        tunnelSocket = tunnel;
        try {
            tunnelOut = new DataOutputStream(tunnel.getOutputStream());
        } catch (Exception e) {
            SimpleLog.i("Error creando tunnelOut: " + e.getMessage());
            return;
        }
        startTunnelReader(tunnel);
        startKeepalive();
        if (bridgeServer == null) startTunnelBridge();
        SimpleLog.i("Bridge local listo en puerto 10809");
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

    private static void startTunnelReader(Socket tunnel) {
        new Thread(() -> {
            try {
                DataInputStream inp = new DataInputStream(tunnel.getInputStream());
                while (running) {
                    byte type = inp.readByte();
                    int streamId = inp.readInt();
                    int length = inp.readInt();
                    byte[] data = length > 0 ? new byte[length] : new byte[0];
                    if (length > 0) inp.readFully(data);

                    if (type == TYPE_DATA) {
                        Socket client = streams.get(streamId);
                        if (client != null) {
                            client.getOutputStream().write(data);
                            client.getOutputStream().flush();
                        }
                    } else if (type == TYPE_CLOSE) {
                        Socket client = streams.remove(streamId);
                        if (client != null) try { client.close(); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }, "tunnel-reader").start();
    }

    private static void startTunnelBridge() {
        try {
            ServerSocket server = new ServerSocket(TUNNEL_LOCAL_PORT, 256, InetAddress.getByName("127.0.0.1"));
            bridgeServer = server;
            SimpleLog.i("Bridge escuchando en 127.0.0.1:10809");
            new Thread(() -> {
                while (running) {
                    try {
                        Socket client = server.accept();
                        client.setTcpNoDelay(true);
                        int streamId = nextStreamId.getAndIncrement();
                        SimpleLog.i("Nueva conexión local stream=" + streamId);
                        streams.put(streamId, client);
                        writeFrame(TYPE_OPEN, streamId, new byte[0]);
                        new Thread(() -> streamClient(streamId, client), "stream-" + streamId).start();
                    } catch (Exception ignored) { break; }
                }
            }, "bridge-accept").start();
        } catch (Exception e) { SimpleLog.i("Error bridge: " + e.getMessage()); }
    }

    private static void streamClient(int streamId, Socket client) {
        byte[] buf = new byte[65536];
        try {
            while (running) {
                int n = client.getInputStream().read(buf);
                if (n < 0) break;
                byte[] payload = new byte[n];
                System.arraycopy(buf, 0, payload, 0, n);
                writeFrame(TYPE_DATA, streamId, payload);
            }
        } catch (Exception ignored) {}
        try { writeFrame(TYPE_CLOSE, streamId, new byte[0]); } catch (Exception ignored) {}
        streams.remove(streamId);
        try { client.close(); } catch (Exception ignored) {}
    }

    private static int countBlocks(String s) {
        int count = 0, idx = 0;
        while ((idx = s.indexOf("\r\n\r\n", idx)) >= 0) { count++; idx += 4; }
        return count;
    }

    private static Socket openTunnel(SocketProtector protector) {
        try {
            Socket socket = openProxySocket(protector);
            if (socket == null) return null;
            socket.setTcpNoDelay(true);

            var out = socket.getOutputStream();
            var inp = socket.getInputStream();

            // P1 y P2 — sin X-Client-Id porque este servidor no autentica
            String p1 = "GET / HTTP/1.1\r\nHost: " + PROXY_HOST + "\r\n\r\n";
            String p2 = "- / HTTP/1.1\r\nHost: " + TUNNEL_HOST + "\r\n"
                      + "Upgrade: websocket\r\nAction: tunnel\r\n\r\n";

            out.write(p1.getBytes()); out.flush();
            SimpleLog.i("P1 enviado");
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            out.write(p2.getBytes()); out.flush();
            SimpleLog.i("P2 enviado");

            // Leer DOS respuestas: 530 de Cloudflare + 101 del servidor
            socket.setSoTimeout(8000);
            StringBuilder raw = new StringBuilder();
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    byte[] tmp = new byte[4096];
                    int n = inp.read(tmp);
                    if (n < 0) break;
                    raw.append(new String(tmp, 0, n));
                    // Esperar dos bloques \r\n\r\n (530 + 101)
                    int count = 0;
                    int idx = 0;
                    while ((idx = raw.indexOf("\r\n\r\n", idx)) >= 0) { count++; idx += 4; }
                    if (count >= 2) break;
                } catch (java.net.SocketTimeoutException ignored) { break; }
            }

            SimpleLog.i("Respuesta cruda: " + raw.toString().replace("\r\n", "|").substring(0, Math.min(raw.length(), 300)));
            if (raw.indexOf("HTTP/1.1 101") < 0) {
                SimpleLog.i("ERROR: no hay 101 — bloques=" + countBlocks(raw.toString()));
                socket.close();
                return null;
            }
            SimpleLog.i("Handshake 101 OK");
            socket.setSoTimeout(0);
            return socket;
        } catch (Exception e) {
            SimpleLog.i("openTunnel excepcion: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return null;
        }
    }

    private static Socket openProxySocket(SocketProtector protector) {
        SimpleLog.i("Conectando a proxy IPv6: " + PROXY_IPV6);
        try {
            Socket s = new Socket();
            protector.protect(s);
            s.setKeepAlive(true);
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(InetAddress.getByName(PROXY_IPV6), PROXY_PORT), 10000);
            SimpleLog.i("Socket IPv6 OK");
            return s;
        } catch (Exception e) { SimpleLog.i("IPv6 falló: " + e.getMessage()); }
        // Fallback: resolver por DNS
        try {
            InetAddress[] addrs = InetAddress.getAllByName(PROXY_HOST);
            for (InetAddress addr : addrs) {
                try {
                    Socket s = new Socket();
                    protector.protect(s);
                    s.setKeepAlive(true);
                    s.setTcpNoDelay(true);
                    s.connect(new InetSocketAddress(addr, PROXY_PORT), 10000);
                    return s;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }
}
