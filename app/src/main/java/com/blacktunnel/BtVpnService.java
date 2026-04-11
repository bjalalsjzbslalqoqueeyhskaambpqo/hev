package com.blacktunnel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import androidx.core.app.NotificationCompat;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.concurrent.atomic.AtomicLong;

public class BtVpnService extends VpnService {
    public static final String ACTION_START = "com.blacktunnel.START";
    public static final String ACTION_STOP  = "com.blacktunnel.STOP";
    public static final String EXTRA_TCP    = "enable_tcp";
    public static final String EXTRA_UDP    = "enable_udp";
    private static final String CH_ID  = "bt_vpn";
    private static final int    NF_ID  = 33;
    private static final int    LOG_MAX = 300;

    private static final ArrayDeque<String> LOGS = new ArrayDeque<>();
    private static final SimpleDateFormat   TS   = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private ParcelFileDescriptor tunPfd;

    public static synchronized void log(String m) {
        String l = TS.format(new Date()) + "  " + m;
        LOGS.addLast(l);
        while (LOGS.size() > LOG_MAX) LOGS.removeFirst();
    }

    public static synchronized String dumpLogs() {
        StringBuilder sb = new StringBuilder();
        for (String l : LOGS) sb.append(l).append('\n');
        return sb.toString();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) { stopAll(); stopSelf(); return START_NOT_STICKY; }
        startAll();
        return START_STICKY;
    }

    private void startAll() {
        if (tunPfd != null) return;
        createChannel();
        startForeground(NF_ID, buildNotif("BlackTunnel activo"));

        tunPfd = new Builder()
                .setSession("bt-hev")
                .setMtu(1280)
                .addAddress("198.18.0.1", 30)
                .addAddress("fc00::1", 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("198.18.0.2")
                .addDnsServer("8.8.8.8")
                .addDisallowedApplication(getPackageName())
                .establish();

        if (tunPfd == null) { log("TUN null"); return; }

        int fd;
        try { fd = ParcelFileDescriptor.dup(tunPfd.getFileDescriptor()).detachFd(); }
        catch (Exception e) { log("dup: " + e.getMessage()); return; }

        File cfg = writeHevCfg();
        CountDownLatch latch = new CountDownLatch(1);
        new Thread(() -> {
            latch.countDown();
            int code = HevBridge.start(cfg.getAbsolutePath(), fd);
            log("hev exit=" + code);
            try { ParcelFileDescriptor.adoptFd(fd).close(); } catch (Exception ignored) {}
        }, "hev").start();

        try { latch.await(500, TimeUnit.MILLISECONDS); Thread.sleep(300); }
        catch (InterruptedException ignored) {}

        Proxy.start(this::protect);
        log("started");
    }

    private void stopAll() {
        Proxy.stop();
        HevBridge.stop();
        if (tunPfd != null) {
            try { tunPfd.close(); } catch (Exception ignored) {}
            tunPfd = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        log("stopped");
    }

    private File writeHevCfg() {
        String yml =
            "tunnel:\n  name: bt-hev\n  mtu: 1280\n  ipv4: 198.18.0.1\n  ipv6: fc00::1\n" +
            "socks5:\n  address: 127.0.0.1\n  port: " + Proxy.PORT + "\n" +
            "  udp: 'tcp'\n  pipeline: true\n" +
            "mapdns:\n  address: 198.18.0.2\n  port: 53\n" +
            "  network: 100.64.0.0\n  netmask: 255.192.0.0\n  cache-size: 4096\n" +
            "misc:\n  task-stack-size: 49152\n  tcp-buffer-size: 28672\n" +
            "  udp-copy-buffer-nums: 16\n  connect-timeout: 5000\n" +
            "  read-write-timeout: 300000\n  udp-read-write-timeout: 60000\n" +
            "  log-level: warn\n";
        File f = new File(getFilesDir(), "hev.yml");
        try {
            FileOutputStream o = new FileOutputStream(f, false);
            o.write(yml.getBytes(StandardCharsets.UTF_8)); o.flush(); o.close();
        } catch (Exception e) { log("hev.yml: " + e.getMessage()); }
        return f;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(
                new NotificationChannel(CH_ID, "BlackTunnel", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification buildNotif(String t) {
        return new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("BlackTunnel").setContentText(t).setOngoing(true).build();
    }

    @Override public void onDestroy() { stopAll(); super.onDestroy(); }

    static final class Proxy {
        static final int PORT = 10809;

        private static final String P_IPV6 = "2606:4700::6812:16b7";
        private static final String P_HOST = "emailmarketing.personal.com.ar";
        private static final int    P_PORT = 80;
        private static final String T_HOST = "3.brawlpass.com.ar";

        private static final byte T_OPEN =0x01,T_DATA=0x02,T_CLOSE=0x03,T_PING=0x04,T_PONG=0x05;
        private static final int  MAX_PL  = 65535;
        private static final long IDLE_MS = 5*60*1000L;
        private static final long WD_INT  = 60*1000L;
        private static final int  WR_BUF  = 8*1024;

        interface Protector { boolean protect(Socket s); }

        private static volatile boolean          running;
        private static volatile Protector        protector;
        private static volatile ServerSocket     relay;
        private static volatile Socket           tun;
        private static volatile DataOutputStream tunOut;
        private static final    Object           lock    = new Object();
        private static final    AtomicInteger    nextSid = new AtomicInteger(1);

        private static final Map<Integer, Socket>         streams  = new ConcurrentHashMap<>();
        private static final Map<Integer, CountDownLatch> latches  = new ConcurrentHashMap<>();
        private static final Map<Integer, AtomicLong>     active   = new ConcurrentHashMap<>();

        static void start(Protector p) {
            protector = p; running = true; nextSid.set(1);
            new Thread(Proxy::connect, "bt-init").start();
        }

        static void stop() {
            running = false; protector = null;
            latches.values().forEach(CountDownLatch::countDown);
            latches.clear(); active.clear();
            try { if (relay != null) relay.close(); } catch (Exception ignored) {}
            relay = null;
            streams.values().forEach(s -> { try { s.close(); } catch (Exception ignored) {} });
            streams.clear();
            try { if (tun != null) tun.close(); } catch (Exception ignored) {}
            tun = null; tunOut = null;
        }

        private static void connect() {
            Socket t = openTunnel();
            if (t == null) { log("túnel null"); return; }
            tun = t;
            try { tunOut = new DataOutputStream(new BufferedOutputStream(t.getOutputStream(), WR_BUF)); }
            catch (Exception e) { log("tunOut: " + e.getMessage()); return; }
            startReader(); startKeepalive(); startWatchdog(); startRelay();
            log("túnel listo");
        }

        private static void startWatchdog() {
            new Thread(() -> {
                while (running) {
                    try { Thread.sleep(WD_INT); } catch (InterruptedException ignored) { break; }
                    long now = System.currentTimeMillis();
                    for (Map.Entry<Integer, AtomicLong> e : active.entrySet()) {
                        int sid = e.getKey();
                        if (now - e.getValue().get() < IDLE_MS) continue;
                        active.remove(sid); Socket s = streams.remove(sid);
                        CountDownLatch l = latches.remove(sid);
                        try { frame(T_CLOSE, sid, new byte[0]); } catch (Exception ignored) {}
                        if (l != null) l.countDown();
                        if (s != null) try { s.close(); } catch (Exception ignored) {}
                    }
                }
            }, "wd").start();
        }

        private static void startRelay() {
            try {
                relay = new ServerSocket(PORT, 512, InetAddress.getByName("127.0.0.1"));
                new Thread(() -> {
                    while (running) {
                        try {
                            Socket c = relay.accept();
                            c.setTcpNoDelay(true);
                            new Thread(() -> handle(c), "rel").start();
                        } catch (Exception ignored) { break; }
                    }
                }, "relay").start();
            } catch (Exception e) { log("relay: " + e.getMessage()); }
        }

        private static void readFully(InputStream in, byte[] buf, int len) throws Exception {
            int off = 0;
            while (off < len) {
                int n = in.read(buf, off, len - off);
                if (n < 0) throw new Exception("EOF");
                off += n;
            }
        }

        private static void handle(Socket c) {
            int sid = -1; CountDownLatch latch = null;
            try {
                sid = nextSid.getAndIncrement();
                latch = new CountDownLatch(1);
                streams.put(sid, c); latches.put(sid, latch);
                active.put(sid, new AtomicLong(System.currentTimeMillis()));

                InputStream  ci = c.getInputStream();
                OutputStream co = c.getOutputStream();
                byte[] tmp = new byte[256];

                ci.read(); int nm = ci.read(); readFully(ci, tmp, nm);
                co.write(new byte[]{0x05, 0x00}); co.flush();

                ci.read(); ci.read(); ci.read();
                int at = ci.read();
                byte[] hb;
                if (at==1) { hb=new byte[4]; readFully(ci,hb,4); }
                else if (at==3) { int l=ci.read(); hb=new byte[l]; readFully(ci,hb,l); }
                else { hb=new byte[16]; readFully(ci,hb,16); }
                byte[] pb = new byte[2]; readFully(ci, pb, 2);

                co.write(new byte[]{0x05,0x00,0x00,0x01,0,0,0,0,0,0}); co.flush();

                byte[] dest;
                if (at==1) {
                    dest=new byte[7]; dest[0]=0x01;
                    System.arraycopy(hb,0,dest,1,4); dest[5]=pb[0]; dest[6]=pb[1];
                } else if (at==3) {
                    dest=new byte[2+hb.length+2]; dest[0]=0x03; dest[1]=(byte)hb.length;
                    System.arraycopy(hb,0,dest,2,hb.length);
                    dest[2+hb.length]=pb[0]; dest[2+hb.length+1]=pb[1];
                } else {
                    dest=new byte[19]; dest[0]=0x04;
                    System.arraycopy(hb,0,dest,1,16); dest[17]=pb[0]; dest[18]=pb[1];
                }

                frame(T_OPEN, sid, dest);
                active.get(sid).set(System.currentTimeMillis());

                byte[] buf = new byte[32768];
                while (running) {
                    int n = ci.read(buf); if (n < 0) break;
                    byte[] chunk = new byte[n];
                    System.arraycopy(buf,0,chunk,0,n);
                    frame(T_DATA, sid, chunk);
                    AtomicLong ts = active.get(sid);
                    if (ts != null) ts.set(System.currentTimeMillis());
                }
                try { frame(T_CLOSE, sid, new byte[0]); } catch (Exception ignored) {}
                latch.await(3, TimeUnit.SECONDS);
            } catch (Exception e) { log("stream: " + e.getMessage()); }
            finally {
                if (sid >= 0) { streams.remove(sid); latches.remove(sid); active.remove(sid); }
                try { c.close(); } catch (Exception ignored) {}
            }
        }

        private static void startReader() {
            new Thread(() -> {
                try {
                    DataInputStream in = new DataInputStream(tun.getInputStream());
                    while (running) {
                        byte t = in.readByte(); int sid = in.readInt();
                        int len = in.readUnsignedShort();
                        byte[] d = len > 0 ? new byte[len] : new byte[0];
                        if (len > 0) in.readFully(d);
                        if (t == T_DATA) {
                            Socket s = streams.get(sid);
                            if (s != null && !s.isClosed()) {
                                try { s.getOutputStream().write(d); s.getOutputStream().flush(); }
                                catch (Exception ignored) {}
                            }
                            AtomicLong ts = active.get(sid);
                            if (ts != null) ts.set(System.currentTimeMillis());
                        } else if (t == T_CLOSE) {
                            CountDownLatch l = latches.remove(sid);
                            if (l != null) l.countDown();
                            streams.remove(sid); active.remove(sid);
                        }
                    }
                } catch (Exception ignored) {}
            }, "reader").start();
        }

        private static void startKeepalive() {
            new Thread(() -> {
                while (running && tun != null && tun.isConnected()) {
                    try { Thread.sleep(30_000); } catch (InterruptedException ignored) { break; }
                    try { frame(T_PING, 0, new byte[0]); } catch (Exception ignored) { break; }
                }
            }, "ka").start();
        }

        private static void frame(byte type, int sid, byte[] data) throws Exception {
            if (data.length > MAX_PL) throw new IllegalArgumentException("payload > 65535");
            synchronized (lock) {
                if (tunOut == null) return;
                tunOut.writeByte(type); tunOut.writeInt(sid);
                tunOut.writeShort(data.length);
                if (data.length > 0) tunOut.write(data);
                tunOut.flush();
            }
        }

        private static Socket openTunnel() {
            try {
                Socket s = openProxy(); if (s == null) return null;
                s.setTcpNoDelay(true);
                OutputStream out = s.getOutputStream();
                InputStream  in  = s.getInputStream();
                out.write(("GET / HTTP/1.1\r\nHost: "+P_HOST+"\r\n\r\n").getBytes()); out.flush();
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                out.write(("- / HTTP/1.1\r\nHost: "+T_HOST+"\r\nUpgrade: websocket\r\nAction: tunnel\r\n\r\n").getBytes());
                out.flush();
                s.setSoTimeout(8000);
                StringBuilder raw = new StringBuilder();
                long dl = System.currentTimeMillis() + 8000;
                while (System.currentTimeMillis() < dl) {
                    try {
                        byte[] tmp = new byte[4096]; int n = in.read(tmp);
                        if (n < 0) break;
                        raw.append(new String(tmp, 0, n));
                        int cnt=0,idx=0;
                        while ((idx=raw.indexOf("\r\n\r\n",idx))>=0){cnt++;idx+=4;}
                        if (cnt >= 2) break;
                    } catch (java.net.SocketTimeoutException ignored) { break; }
                }
                if (!raw.toString().contains("101")) { log("sin 101"); s.close(); return null; }
                s.setSoTimeout(0); return s;
            } catch (Exception e) { log("openTunnel: " + e.getMessage()); return null; }
        }

        private static Socket openProxy() {
            Protector p = protector;
            try {
                Socket s = new Socket(); if (p != null) p.protect(s);
                s.setKeepAlive(true); s.setTcpNoDelay(true);
                s.setReceiveBufferSize(262144); s.setSendBufferSize(262144);
                s.connect(new InetSocketAddress(InetAddress.getByName(P_IPV6), P_PORT), 10000);
                log("IPv6 OK"); return s;
            } catch (Exception e) { log("IPv6: " + e.getMessage()); }
            try {
                for (InetAddress a : InetAddress.getAllByName(P_HOST)) {
                    try {
                        Socket s = new Socket(); if (p != null) p.protect(s);
                        s.setKeepAlive(true); s.setTcpNoDelay(true);
                        s.setReceiveBufferSize(262144); s.setSendBufferSize(262144);
                        s.connect(new InetSocketAddress(a, P_PORT), 10000);
                        log("DNS OK"); return s;
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            return null;
        }
    }
}
