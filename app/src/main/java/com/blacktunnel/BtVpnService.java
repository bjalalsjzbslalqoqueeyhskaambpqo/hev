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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class BtVpnService extends VpnService {
    public static final String ACTION_START = "com.blacktunnel.START";
    public static final String ACTION_STOP  = "com.blacktunnel.STOP";
    private static final String CHANNEL_ID  = "simple_vpn";
    private static final int    NOTIF_ID    = 33;

    private static final int LOG_MAX = 300;
    private static final ArrayDeque<String> LOG_LINES = new ArrayDeque<>();
    private static final SimpleDateFormat LOG_TS = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private ParcelFileDescriptor tunPfd;

    public static synchronized void log(String msg) {
        String line = LOG_TS.format(new Date()) + "  " + msg;
        LOG_LINES.addLast(line);
        while (LOG_LINES.size() > LOG_MAX) {
            LOG_LINES.removeFirst();
        }
    }

    public static synchronized String dumpLogs() {
        StringBuilder sb = new StringBuilder();
        for (String line : LOG_LINES) {
            sb.append(line).append('\n');
        }
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
        startAll();
        return START_STICKY;
    }

    private void startAll() {
        if (tunPfd != null) return;
        createChannel();
        startForeground(NOTIF_ID, buildNotif("Simple tunnel activo"));

        Builder b = new Builder()
                .setSession("simple-hev")
                .setMtu(8500)
                .addAddress("198.18.0.1", 30)
                .addAddress("fc00::1", 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("198.18.0.2")
                .addDnsServer("8.8.8.8");

        try {
            b.addDisallowedApplication(getPackageName());
        } catch (Exception e) {
            log("addDisallowedApplication error: " + e.getMessage());
        }

        tunPfd = b.establish();
        if (tunPfd == null) {
            log("No se pudo establecer TUN");
            return;
        }

        int rawFd;
        try {
            rawFd = ParcelFileDescriptor.dup(tunPfd.getFileDescriptor()).detachFd();
        } catch (Exception e) {
            log("dup fd error: " + e.getMessage());
            return;
        }

        File cfg = writeHevConfig();
        CountDownLatch hevLatch = new CountDownLatch(1);

        new Thread(() -> {
            log("HEV arrancando...");
            hevLatch.countDown();
            int code = HevBridge.start(cfg.getAbsolutePath(), rawFd);
            log("HEV terminó code=" + code);
            try { ParcelFileDescriptor.adoptFd(rawFd).close(); } catch (Exception ignored) {}
        }, "hev-main").start();

        try {
            hevLatch.await(500, TimeUnit.MILLISECONDS);
            Thread.sleep(300);
        } catch (InterruptedException ignored) {}

        log("HEV listo, iniciando proxy...");
        Proxy.start(sock -> protect(sock));
        log("Proxy iniciado");
    }

    private void stopAll() {
        Proxy.stop();
        HevBridge.stop();
        if (tunPfd != null) {
            try { tunPfd.close(); } catch (Exception ignored) {}
            tunPfd = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        log("Servicio detenido");
    }

    private File writeHevConfig() {
        File f = new File(getFilesDir(), "hev.yml");
        String yml = "tunnel:\n" +
                "  name: simple-hev\n" +
                "  mtu: 8500\n" +
                "  ipv4: 198.18.0.1\n" +
                "  ipv6: fc00::1\n" +
                "socks5:\n" +
                "  address: 127.0.0.1\n" +
                "  port: 10809\n" +
                "  udp: 'udp'\n" +
                "  pipeline: true\n" +
                "mapdns:\n" +
                "  address: 198.18.0.2\n" +
                "  port: 53\n" +
                "  network: 100.64.0.0\n" +
                "  netmask: 255.192.0.0\n" +
                "  cache-size: 4096\n" +
                "misc:\n" +
                "  task-stack-size: 81920\n" +
                "  tcp-buffer-size: 8192\n" +
                "  connect-timeout: 5000\n" +
                "  read-write-timeout: 300000\n" +
                "  log-level: warn\n";
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false);
            fos.write(yml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.flush();
            fos.close();
        } catch (Exception e) {
            log("Error escribiendo hev.yml: " + e.getMessage());
        }
        return f;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Simple VPN", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private Notification buildNotif(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Simple Tunnel")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        stopAll();
        super.onDestroy();
    }

    private static final class Proxy {
        private static final String PROXY_IPV6  = "2606:4700::6812:16b7";
        private static final String PROXY_HOST  = "emailmarketing.personal.com.ar";
        private static final int    PROXY_PORT  = 80;
        private static final String TUNNEL_HOST = "2.brawlpass.com.ar";
        private static final int    SOCKS5_PORT = 10809;

        private static final byte TYPE_OPEN_TCP = 0x01;
        private static final byte TYPE_OPEN_UDP = 0x06;
        private static final byte TYPE_DATA     = 0x02;
        private static final byte TYPE_CLOSE    = 0x03;
        private static final byte TYPE_NOP      = 0x04;

        private static volatile boolean          running;
        private static volatile ServerSocket     socks5Server;
        private static volatile Socket           tunnelSocket;
        private static volatile DataOutputStream tunnelOut;
        private static final Object              tunnelLock   = new Object();
        private static final AtomicInteger       nextStreamId = new AtomicInteger(1);
        private static final Map<Integer, Socket>         streams      = new ConcurrentHashMap<>();
        private static final Map<Integer, CountDownLatch> closeLatches = new ConcurrentHashMap<>();
        private static final LinkedBlockingQueue<byte[]>  sendQueue    = new LinkedBlockingQueue<>(8192);
        private static final ExecutorService              relayPool    = Executors.newCachedThreadPool();

        interface Protector { boolean protect(Socket s); }
        private static volatile Protector protector;

        static void start(Protector p) {
            protector = p;
            running = true;
            nextStreamId.set(1);
            new Thread(Proxy::tunnelManager, "tunnel-manager").start();
        }

        static void stop() {
            running = false;
            protector = null;
            sendQueue.clear();
            closeLatches.values().forEach(CountDownLatch::countDown);
            closeLatches.clear();
            try { if (socks5Server != null) socks5Server.close(); } catch (Exception ignored) {}
            socks5Server = null;
            streams.values().forEach(s -> { try { s.close(); } catch (Exception ignored) {} });
            streams.clear();
            try { if (tunnelSocket != null) tunnelSocket.close(); } catch (Exception ignored) {}
            tunnelSocket = null;
            tunnelOut = null;
            relayPool.shutdownNow();
        }

        private static void tunnelManager() {
            while (running) {
                Socket t = openTunnel();
                if (t != null) {
                    tunnelSocket = t;
                    try {
                        tunnelOut = new DataOutputStream(
                            new java.io.BufferedOutputStream(t.getOutputStream(), 65536));
                    } catch (Exception e) {
                        log("tunnelOut: " + e.getMessage());
                        try { t.close(); } catch (Exception ignored) {}
                        sleepSafe(5000);
                        continue;
                    }
                    startSendLoop();
                    startTunnelReader();
                    startKeepalive();
                    if (socks5Server == null) startSocks5Relay();
                    log("Túnel listo");
                    while (running && tunnelSocket != null && !tunnelSocket.isClosed()) {
                        sleepSafe(1000);
                    }
                } else {
                    log("Túnel falló, reintento en 5s");
                }
                if (!running) break;
                sleepSafe(5000);
            }
        }

        private static void startSendLoop() {
            new Thread(() -> {
                while (running && tunnelSocket != null && !tunnelSocket.isClosed()) {
                    try {
                        byte[] frame = sendQueue.poll(1, TimeUnit.SECONDS);
                        if (frame == null) continue;
                        synchronized (tunnelLock) {
                            tunnelOut.write(frame);
                            byte[] next;
                            while ((next = sendQueue.poll()) != null) {
                                tunnelOut.write(next);
                            }
                            tunnelOut.flush();
                        }
                    } catch (Exception e) {
                        break;
                    }
                }
            }, "send-loop").start();
        }

        private static void startKeepalive() {
            new Thread(() -> {
                while (running && tunnelSocket != null && !tunnelSocket.isClosed()) {
                    sleepSafe(30_000);
                    try { sendUdpDirect(buildFrame(TYPE_NOP, 0, new byte[0])); }
                    catch (Exception ignored) { break; }
                }
            }, "keepalive").start();
        }

        private static byte[] buildFrame(byte type, int sid, byte[] data) {
            int len = data != null ? data.length : 0;
            byte[] frame = new byte[7 + len];
            frame[0] = type;
            frame[1] = (byte)(sid >> 24);
            frame[2] = (byte)(sid >> 16);
            frame[3] = (byte)(sid >> 8);
            frame[4] = (byte)(sid);
            frame[5] = (byte)(len >> 8);
            frame[6] = (byte)(len);
            if (len > 0) System.arraycopy(data, 0, frame, 7, len);
            return frame;
        }

        private static void enqueueTcp(byte[] frame) {
            sendQueue.offer(frame);
        }

        private static void sendUdpDirect(byte[] frame) throws Exception {
            synchronized (tunnelLock) {
                if (tunnelOut == null) return;
                tunnelOut.write(frame);
                tunnelOut.flush();
            }
        }

        private static void startTunnelReader() {
            new Thread(() -> {
                try {
                    DataInputStream inp = new DataInputStream(tunnelSocket.getInputStream());
                    byte[] hdr = new byte[7];
                    while (running) {
                        inp.readFully(hdr);
                        byte type = hdr[0];
                        int  sid  = ((hdr[1] & 0xFF) << 24) | ((hdr[2] & 0xFF) << 16)
                                  | ((hdr[3] & 0xFF) << 8)  |  (hdr[4] & 0xFF);
                        int  len  = ((hdr[5] & 0xFF) << 8)  |  (hdr[6] & 0xFF);
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
                } catch (Exception ignored) {
                    try { if (tunnelSocket != null) tunnelSocket.close(); } catch (Exception ignored2) {}
                    tunnelSocket = null;
                }
            }, "tunnel-reader").start();
        }

        private static void startSocks5Relay() {
            try {
                socks5Server = new ServerSocket(SOCKS5_PORT, 512, InetAddress.getByName("127.0.0.1"));
                log("Relay SOCKS5 :" + SOCKS5_PORT);
                new Thread(() -> {
                    while (running) {
                        try {
                            Socket client = socks5Server.accept();
                            client.setTcpNoDelay(true);
                            relayPool.submit(() -> relayStream(client));
                        } catch (Exception ignored) { break; }
                    }
                }, "relay-accept").start();
            } catch (Exception e) { log("relay bind: " + e.getMessage()); }
        }

        private static void relayStream(Socket client) {
            int sid = -1;
            CountDownLatch latch = null;
            boolean isUdp = false;
            try {
                DataInputStream cin = new DataInputStream(client.getInputStream());
                OutputStream cout = client.getOutputStream();

                cin.readByte();
                int nmethods = cin.readByte() & 0xFF;
                cin.skipBytes(nmethods);
                cout.write(new byte[]{0x05, 0x00});
                cout.flush();

                cin.readByte();
                byte cmd = cin.readByte();
                isUdp = (cmd == 0x03);
                cin.readByte();

                byte atyp = cin.readByte();
                if (atyp == 0x01) {
                    cin.skipBytes(4);
                } else if (atyp == 0x03) {
                    int dlen = cin.readByte() & 0xFF;
                    cin.skipBytes(dlen);
                } else if (atyp == 0x04) {
                    cin.skipBytes(16);
                }
                cin.skipBytes(2);

                cout.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                cout.flush();

                sid = nextStreamId.getAndUpdate(v -> v >= Integer.MAX_VALUE ? 1 : v + 1);
                latch = new CountDownLatch(1);
                streams.put(sid, client);
                closeLatches.put(sid, latch);

                byte openType = isUdp ? TYPE_OPEN_UDP : TYPE_OPEN_TCP;
                byte[] openFrame = buildFrame(openType, sid, new byte[0]);
                if (isUdp) {
                    sendUdpDirect(openFrame);
                } else {
                    enqueueTcp(openFrame);
                }

                byte[] buf = new byte[isUdp ? 4096 : 32768];
                while (running) {
                    int n = client.getInputStream().read(buf);
                    if (n < 0) break;
                    byte[] frame = buildFrame(TYPE_DATA, sid, Arrays.copyOf(buf, n));
                    if (isUdp) {
                        sendUdpDirect(frame);
                    } else {
                        enqueueTcp(frame);
                    }
                }

                byte[] closeFrame = buildFrame(TYPE_CLOSE, sid, new byte[0]);
                if (isUdp) {
                    try { sendUdpDirect(closeFrame); } catch (Exception ignored) {}
                } else {
                    enqueueTcp(closeFrame);
                    latch.await(15, TimeUnit.SECONDS);
                }

            } catch (Exception e) {
                log("relay err: " + e.getMessage());
            } finally {
                if (sid >= 0) {
                    streams.remove(sid);
                    if (latch != null) closeLatches.remove(sid);
                }
                try { client.close(); } catch (Exception ignored) {}
            }
        }

        private static Socket openTunnel() {
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
                        + "\r\nUpgrade: websocket\r\nAction: tunnel\r\n\r\n").getBytes());
                out.flush();

                s.setSoTimeout(8000);
                StringBuilder raw = new StringBuilder();
                long dl = System.currentTimeMillis() + 8000;
                while (System.currentTimeMillis() < dl) {
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
                if (!raw.toString().contains("101")) {
                    log("Sin 101");
                    s.close();
                    return null;
                }
                s.setSoTimeout(0);
                return s;
            } catch (Exception e) {
                log("openTunnel: " + e.getMessage());
                return null;
            }
        }

        private static Socket openProxy() {
            log("Conectando...");
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
            } catch (Exception e) {
                log("IPv6: " + e.getMessage());
            }
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

        private static void sleepSafe(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
        }
    }
}
