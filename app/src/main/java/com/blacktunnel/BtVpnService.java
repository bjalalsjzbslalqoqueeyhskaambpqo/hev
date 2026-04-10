package com.blacktunnel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.core.app.NotificationCompat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class BtVpnService extends VpnService {
    public static final String ACTION_START        = "com.blacktunnel.START";
    public static final String ACTION_STOP         = "com.blacktunnel.STOP";
    public static final String EXTRA_ENABLE_TCP    = "enable_tcp";
    public static final String EXTRA_ENABLE_UDP    = "enable_udp";
    private static final String CHANNEL_ID         = "bt_vpn";
    private static final int    NOTIF_ID           = 33;
    private static final int    LOG_MAX            = 300;

    private static final ArrayDeque<String>  LOG_LINES = new ArrayDeque<>();
    private static final SimpleDateFormat    LOG_TS    = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private ParcelFileDescriptor tunPfd;

    public static synchronized void log(String msg) {
        String line = LOG_TS.format(new Date()) + "  " + msg;
        LOG_LINES.addLast(line);
        while (LOG_LINES.size() > LOG_MAX) LOG_LINES.removeFirst();
    }

    public static synchronized String dumpLogs() {
        StringBuilder sb = new StringBuilder();
        for (String l : LOG_LINES) sb.append(l).append('\n');
        return sb.toString();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopAll();
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean enableTcp = intent == null || intent.getBooleanExtra(EXTRA_ENABLE_TCP, true);
        boolean enableUdp = intent == null || intent.getBooleanExtra(EXTRA_ENABLE_UDP, true);
        startAll(enableTcp, enableUdp);
        return START_STICKY;
    }

    private void startAll(boolean enableTcp, boolean enableUdp) {
        if (tunPfd != null) return;
        createChannel();
        startForeground(NOTIF_ID, buildNotif("BlackTunnel activo"));

        Builder b = new Builder()
                .setSession("bt-hev")
                .setMtu(8500)
                .addAddress("198.18.0.1", 30)
                .addAddress("fc00::1", 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("198.18.0.2")
                .addDnsServer("8.8.8.8");

        try { b.addDisallowedApplication(getPackageName()); } catch (Exception ignored) {}

        tunPfd = b.establish();
        if (tunPfd == null) { log("TUN null"); return; }

        int rawFd;
        try {
            rawFd = ParcelFileDescriptor.dup(tunPfd.getFileDescriptor()).detachFd();
        } catch (Exception e) { log("dup fd: " + e.getMessage()); return; }

        File cfg = writeHevConfig(enableTcp, enableUdp);
        CountDownLatch hevLatch = new CountDownLatch(1);
        new Thread(() -> {
            hevLatch.countDown();
            int code = HevBridge.start(cfg.getAbsolutePath(), rawFd);
            log("HEV terminó code=" + code);
            try { ParcelFileDescriptor.adoptFd(rawFd).close(); } catch (Exception ignored) {}
        }, "hev-main").start();

        try {
            hevLatch.await(500, TimeUnit.MILLISECONDS);
            Thread.sleep(300);
        } catch (InterruptedException ignored) {}

        Proxy.start(this::protect, enableTcp, enableUdp);
        log("Proxy iniciado tcp=" + enableTcp + " udp=" + enableUdp);
    }

    private void stopAll() {
        Proxy.stop();
        HevBridge.stop();
        if (tunPfd != null) {
            try { tunPfd.close(); } catch (Exception ignored) {}
            tunPfd = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        log("Detenido");
    }

    private File writeHevConfig(boolean enableTcp, boolean enableUdp) {
        int socks5Port = (enableTcp && !enableUdp) ? Proxy.SOCKS5_TCP_PORT :
                         (!enableTcp && enableUdp) ? Proxy.SOCKS5_UDP_PORT :
                         Proxy.SOCKS5_TCP_PORT;
        String udpMode = enableUdp ? "'udp'" : "'tcp'";
        String udpAddr = (enableTcp && enableUdp) ?
                "  udp-address: '127.0.0.1'\n" : "";

        String yml = "tunnel:\n" +
                "  name: bt-hev\n" +
                "  mtu: 8500\n" +
                "  ipv4: 198.18.0.1\n" +
                "  ipv6: fc00::1\n" +
                "socks5:\n" +
                "  address: 127.0.0.1\n" +
                "  port: " + socks5Port + "\n" +
                "  udp: " + udpMode + "\n" +
                (enableTcp && enableUdp ? "  udp-address: '127.0.0.1'\n" : "") +
                "  pipeline: true\n" +
                "mapdns:\n" +
                "  address: 198.18.0.2\n" +
                "  port: 53\n" +
                "  network: 100.64.0.0\n" +
                "  netmask: 255.192.0.0\n" +
                "  cache-size: 4096\n" +
                "misc:\n" +
                "  task-stack-size: 49152\n" +
                "  tcp-buffer-size: 28672\n" +
                "  udp-copy-buffer-nums: 16\n" +
                "  connect-timeout: 5000\n" +
                "  read-write-timeout: 300000\n" +
                "  udp-read-write-timeout: 60000\n" +
                "  log-level: warn\n";

        File f = new File(getFilesDir(), "hev.yml");
        try {
            FileOutputStream fos = new FileOutputStream(f, false);
            fos.write(yml.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();
        } catch (Exception e) { log("hev.yml: " + e.getMessage()); }
        return f;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, "BlackTunnel", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification buildNotif(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("BlackTunnel")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() { stopAll(); super.onDestroy(); }

    static final class Proxy {
        static final int SOCKS5_TCP_PORT = 10809;
        static final int SOCKS5_UDP_PORT = 10810;

        private static final String PROXY_IPV6  = "2606:4700::6812:16b7";
        private static final String PROXY_HOST  = "emailmarketing.personal.com.ar";
        private static final int    PROXY_PORT  = 80;
        private static final String TUNNEL_HOST = "2.brawlpass.com.ar";

        private static final byte TYPE_OPEN  = 0x01;
        private static final byte TYPE_DATA  = 0x02;
        private static final byte TYPE_CLOSE = 0x03;
        private static final byte TYPE_PING  = 0x04;
        private static final byte TYPE_PONG  = 0x05;
        private static final byte STREAM_TCP = 0x00;
        private static final byte STREAM_UDP = 0x01;
        private static final int  MAX_PAYLOAD = 65535;

        interface Protector { boolean protect(Socket s); }
        interface UdpProtector { boolean protect(DatagramSocket s); }

        private static volatile boolean          running;
        private static volatile Protector        protector;

        private static volatile ServerSocket     tcpRelayServer;
        private static volatile Socket           tcpTunnel;
        private static volatile DataOutputStream tcpTunnelOut;
        private static final    Object           tcpLock      = new Object();
        private static final    AtomicInteger    tcpStreamId  = new AtomicInteger(1);
        private static final Map<Integer, Socket>         tcpStreams  = new ConcurrentHashMap<>();
        private static final Map<Integer, CountDownLatch> tcpLatches  = new ConcurrentHashMap<>();

        private static volatile DatagramSocket   udpRelaySocket;
        private static volatile Socket           udpTunnel;
        private static volatile DataOutputStream udpTunnelOut;
        private static final    Object           udpLock      = new Object();

        static void start(Protector p, boolean enableTcp, boolean enableUdp) {
            protector = p;
            running   = true;
            tcpStreamId.set(1);
            if (enableTcp) new Thread(() -> connectTcpTunnel(enableUdp), "bt-tcp-init").start();
            if (enableUdp && !enableTcp) new Thread(Proxy::connectUdpTunnel, "bt-udp-init").start();
        }

        static void stop() {
            running   = false;
            protector = null;

            tcpLatches.values().forEach(CountDownLatch::countDown);
            tcpLatches.clear();
            try { if (tcpRelayServer != null) tcpRelayServer.close(); } catch (Exception ignored) {}
            tcpRelayServer = null;
            tcpStreams.values().forEach(s -> { try { s.close(); } catch (Exception ignored) {} });
            tcpStreams.clear();
            try { if (tcpTunnel != null) tcpTunnel.close(); } catch (Exception ignored) {}
            tcpTunnel    = null;
            tcpTunnelOut = null;

            try { if (udpRelaySocket != null) udpRelaySocket.close(); } catch (Exception ignored) {}
            udpRelaySocket = null;
            try { if (udpTunnel != null) udpTunnel.close(); } catch (Exception ignored) {}
            udpTunnel    = null;
            udpTunnelOut = null;
        }

        private static void connectTcpTunnel(boolean alsoStartUdp) {
            Socket t = openTunnel("tunnel");
            if (t == null) { log("TCP túnel null"); return; }
            tcpTunnel = t;
            try { tcpTunnelOut = new DataOutputStream(t.getOutputStream()); }
            catch (Exception e) { log("tcpTunnelOut: " + e.getMessage()); return; }
            startTcpTunnelReader();
            startTcpKeepalive();
            startTcpRelay();
            if (alsoStartUdp) new Thread(Proxy::connectUdpTunnel, "bt-udp-init").start();
            log("Túnel TCP listo");
        }

        private static void connectUdpTunnel() {
            Socket t = openTunnel("tunnel-udp");
            if (t == null) { log("UDP túnel null"); return; }
            udpTunnel = t;
            try { udpTunnelOut = new DataOutputStream(t.getOutputStream()); }
            catch (Exception e) { log("udpTunnelOut: " + e.getMessage()); return; }
            startUdpTunnelReader();
            startUdpRelay();
            log("Túnel UDP listo");
        }

        private static void startTcpRelay() {
            try {
                tcpRelayServer = new ServerSocket(SOCKS5_TCP_PORT, 512, InetAddress.getByName("127.0.0.1"));
                new Thread(() -> {
                    while (running) {
                        try {
                            Socket client = tcpRelayServer.accept();
                            client.setTcpNoDelay(true);
                            new Thread(() -> handleTcpStream(client), "tcp-relay").start();
                        } catch (Exception ignored) { break; }
                    }
                }, "tcp-relay-accept").start();
            } catch (Exception e) { log("tcpRelay bind: " + e.getMessage()); }
        }

        private static void handleTcpStream(Socket client) {
            int sid = -1;
            CountDownLatch latch = null;
            try {
                sid   = tcpStreamId.getAndIncrement();
                latch = new CountDownLatch(1);
                tcpStreams.put(sid, client);
                tcpLatches.put(sid, latch);

                byte[] buf      = new byte[32768];
                int    firstLen = client.getInputStream().read(buf);
                if (firstLen < 0) return;

                byte[] first = new byte[firstLen];
                System.arraycopy(buf, 0, first, 0, firstLen);

                writeTcpFrame(TYPE_OPEN, sid, new byte[]{ STREAM_TCP });
                writeTcpFrame(TYPE_DATA, sid, first);

                while (running) {
                    int n = client.getInputStream().read(buf);
                    if (n < 0) break;
                    byte[] chunk = new byte[n];
                    System.arraycopy(buf, 0, chunk, 0, n);
                    writeTcpFrame(TYPE_DATA, sid, chunk);
                }
                try { writeTcpFrame(TYPE_CLOSE, sid, new byte[0]); } catch (Exception ignored) {}
                latch.await(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                log("tcp stream err: " + e.getMessage());
            } finally {
                if (sid >= 0) { tcpStreams.remove(sid); if (latch != null) tcpLatches.remove(sid); }
                try { client.close(); } catch (Exception ignored) {}
            }
        }

        private static void startUdpRelay() {
            try {
                udpRelaySocket = new DatagramSocket(SOCKS5_UDP_PORT, InetAddress.getByName("127.0.0.1"));
                new Thread(() -> {
                    byte[] buf = new byte[65535];
                    while (running) {
                        try {
                            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                            udpRelaySocket.receive(pkt);
                            byte[] data = new byte[pkt.getLength()];
                            System.arraycopy(buf, 0, data, 0, pkt.getLength());
                            writeUdpRaw(data);
                        } catch (Exception ignored) { break; }
                    }
                }, "udp-relay-recv").start();
            } catch (Exception e) { log("udpRelay bind: " + e.getMessage()); }
        }

        private static void writeUdpRaw(byte[] data) {
            synchronized (udpLock) {
                try {
                    if (udpTunnelOut == null) return;
                    udpTunnelOut.writeShort(data.length);
                    udpTunnelOut.write(data);
                    udpTunnelOut.flush();
                } catch (Exception ignored) {}
            }
        }

        private static void startUdpTunnelReader() {
            new Thread(() -> {
                try {
                    DataInputStream inp = new DataInputStream(udpTunnel.getInputStream());
                    while (running) {
                        int    len  = inp.readUnsignedShort();
                        byte[] data = new byte[len];
                        inp.readFully(data);
                        DatagramSocket relay = udpRelaySocket;
                        if (relay != null && !relay.isClosed()) {
                            DatagramPacket pkt = new DatagramPacket(
                                    data, data.length,
                                    InetAddress.getByName("127.0.0.1"), SOCKS5_UDP_PORT);
                            relay.send(pkt);
                        }
                    }
                } catch (Exception ignored) {}
            }, "udp-tunnel-reader").start();
        }

        private static void startTcpTunnelReader() {
            new Thread(() -> {
                try {
                    DataInputStream inp = new DataInputStream(tcpTunnel.getInputStream());
                    while (running) {
                        byte   type = inp.readByte();
                        int    sid  = inp.readInt();
                        int    len  = inp.readUnsignedShort();
                        byte[] data = len > 0 ? new byte[len] : new byte[0];
                        if (len > 0) inp.readFully(data);

                        if (type == TYPE_DATA) {
                            Socket s = tcpStreams.get(sid);
                            if (s != null && !s.isClosed()) {
                                try { s.getOutputStream().write(data); s.getOutputStream().flush(); }
                                catch (Exception ignored) {}
                            }
                        } else if (type == TYPE_CLOSE) {
                            CountDownLatch l = tcpLatches.remove(sid);
                            if (l != null) l.countDown();
                            tcpStreams.remove(sid);
                        }
                    }
                } catch (Exception ignored) {}
            }, "tcp-tunnel-reader").start();
        }

        private static void startTcpKeepalive() {
            new Thread(() -> {
                while (running && tcpTunnel != null && tcpTunnel.isConnected()) {
                    try { Thread.sleep(30_000); } catch (InterruptedException ignored) {}
                    try { writeTcpFrame(TYPE_PING, 0, new byte[0]); }
                    catch (Exception ignored) { break; }
                }
            }, "tcp-keepalive").start();
        }

        private static void writeTcpFrame(byte type, int sid, byte[] data) throws Exception {
            if (data.length > MAX_PAYLOAD) throw new IllegalArgumentException("payload > 65535");
            synchronized (tcpLock) {
                if (tcpTunnelOut == null) return;
                tcpTunnelOut.writeByte(type);
                tcpTunnelOut.writeInt(sid);
                tcpTunnelOut.writeShort(data.length);
                if (data.length > 0) tcpTunnelOut.write(data);
                tcpTunnelOut.flush();
            }
        }

        private static Socket openTunnel(String action) {
            try {
                Socket s = openProxy();
                if (s == null) return null;
                s.setTcpNoDelay(true);
                OutputStream out = s.getOutputStream();
                InputStream  inp = s.getInputStream();

                out.write(("GET / HTTP/1.1\r\nHost: " + PROXY_HOST + "\r\n\r\n").getBytes());
                out.flush();
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                out.write(("- / HTTP/1.1\r\nHost: " + TUNNEL_HOST
                        + "\r\nUpgrade: websocket\r\nAction: " + action + "\r\n\r\n").getBytes());
                out.flush();

                s.setSoTimeout(8000);
                StringBuilder raw = new StringBuilder();
                long dl = System.currentTimeMillis() + 8000;
                while (System.currentTimeMillis() < dl) {
                    try {
                        byte[] tmp = new byte[4096];
                        int    n   = inp.read(tmp);
                        if (n < 0) break;
                        raw.append(new String(tmp, 0, n));
                        int cnt = 0, idx = 0;
                        while ((idx = raw.indexOf("\r\n\r\n", idx)) >= 0) { cnt++; idx += 4; }
                        if (cnt >= 2) break;
                    } catch (java.net.SocketTimeoutException ignored) { break; }
                }
                if (!raw.toString().contains("101")) {
                    log("Sin 101 (" + action + ")");
                    s.close();
                    return null;
                }
                s.setSoTimeout(0);
                return s;
            } catch (Exception e) {
                log("openTunnel(" + action + "): " + e.getMessage());
                return null;
            }
        }

        private static Socket openProxy() {
            Protector p = protector;
            try {
                Socket s = new Socket();
                if (p != null) p.protect(s);
                s.setKeepAlive(true);
                s.setTcpNoDelay(true);
                s.setReceiveBufferSize(262144);
                s.setSendBufferSize(262144);
                s.connect(new InetSocketAddress(InetAddress.getByName(PROXY_IPV6), PROXY_PORT), 10000);
                log("IPv6 OK");
                return s;
            } catch (Exception e) { log("IPv6: " + e.getMessage()); }
            try {
                for (InetAddress a : InetAddress.getAllByName(PROXY_HOST)) {
                    try {
                        Socket s = new Socket();
                        if (p != null) p.protect(s);
                        s.setKeepAlive(true);
                        s.setTcpNoDelay(true);
                        s.setReceiveBufferSize(262144);
                        s.setSendBufferSize(262144);
                        s.connect(new InetSocketAddress(a, PROXY_PORT), 10000);
                        log("DNS OK");
                        return s;
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            return null;
        }
    }
}
