package com.blacktunnel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class BtVpnService extends VpnService {

    public static final String ACTION_START = "com.blacktunnel.START";
    public static final String ACTION_STOP  = "com.blacktunnel.STOP";
    public static final String ACTION_APPLY = "com.blacktunnel.APPLY";

    private static final String CH_ID = "bt_vpn";
    private static final int    NF_ID = 33;

    private static final Object L_MU = new Object();
    private static final StringBuilder L_BUF = new StringBuilder(8192);
    private static final int L_MAX = 24000;
    private static final long HS_TO = 12000L;

    private static boolean sTunnelOk = false;
    private static volatile boolean sRunning = false;
    private static java.util.concurrent.CountDownLatch sTunnelLatch = null;

    private final ExecutorService ex = Executors.newSingleThreadExecutor();

    private volatile boolean              run     = false;
    private volatile boolean              stop    = false;
    private volatile ParcelFileDescriptor tun      = null;
    private volatile int                  hFd    = -1;
    private volatile Thread               hTh   = null;
    private volatile File                 hCfg  = null;
    private volatile ConnectivityManager.NetworkCallback nCb = null;

    public static boolean iRun() { return sRunning; }

    public static void log(String message) {
        String line = "[" + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())
                    + "] " + message + "\n";
        synchronized (L_MU) {
            L_BUF.append(line);
            if (L_BUF.length() > L_MAX)
                L_BUF.delete(0, L_BUF.length() - L_MAX);
        }
    }

    public static void onLog(String level, String message) {
        String line = level + " " + message;
        log(line);
        if (!sTunnelOk && message != null && message.contains("tunnel ok")) {
            sTunnelOk = true;
            if (sTunnelLatch != null) { sTunnelLatch.countDown(); sTunnelLatch = null; }
        }
        if (BtProxy.logListener != null) BtProxy.logListener.accept(line);
    }

    public static boolean tunnelOk() { return sTunnelOk; }

    public static void onStateChange(String state) {
        switch (state) {
            case "running": sRunning = true; break;
            case "stopped": sRunning = false; break;
        }
        if (BtProxy.stateListener != null) BtProxy.stateListener.accept(state);
    }

    public static String dLogs() {
        synchronized (L_MU) { return L_BUF.toString(); }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        ex.execute(() -> {
            try {
                if (ACTION_STOP.equals(action)) stopAll();
                else startAll();
            } catch (Throwable t) {
                log("E crash: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                recoverFromCrash();
            }
        });
        if (ACTION_STOP.equals(action)) return START_NOT_STICKY;
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ex.execute(this::stopAll);
        ex.shutdown();
        try { ex.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        super.onDestroy();
    }

    private void startAll() {
        if (stop) {
            log("W startAll: stop en progreso, reintentando breve");
            try { Thread.sleep(450); } catch (InterruptedException ignored) { return; }
            if (stop) { log("W startAll: cancelado porque stop sigue en progreso"); return; }
        }
        if (run) { log("I startAll: ya corriendo, ignorado"); return; }

        createChannel();
        startForeground(NF_ID, buildNotif());

        Intent prep = VpnService.prepare(this);
        if (prep != null) { log("W VPN not authorized"); startActivity(prep); stopForeground(STOP_FOREGROUND_REMOVE); return; }

        BtProxy.stop();
        cleanupSessionResources();
        BtProxy.doRegisterCallbacks();

        sTunnelOk = false;
        sTunnelLatch = new java.util.concurrent.CountDownLatch(1);

        String iid = BtProxy.gIid(this);
        if (BtProxy.start(this, iid) < 0) {
            sTunnelLatch = null;
            abortStart("E btproxy start failed");
            return;
        }

        boolean ok = false;
        try { ok = sTunnelLatch.await(HS_TO, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        sTunnelLatch = null;
        if (!ok) { abortStart("E tunnel handshake timeout"); return; }

        registerNet();
        if (!startHevStack())                     { unregisterNet(); abortStart("E startHevStack failed"); return; }

        run = sRunning = true;
        log("I startAll ok");
    }

    private void abortStart(String reason) {
        log(reason);
        BtProxy.stop();
        cleanupSessionResources();
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void recoverFromCrash() {
        run = false; stop = false; sRunning = false; sTunnelOk = false;
        try { stopHevStack(); } catch (Throwable ignored) {}
        try { BtProxy.stop(); } catch (Throwable ignored) {}
        try { unregisterNet(); } catch (Throwable ignored) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}
        try { stopSelf(); } catch (Throwable ignored) {}
    }

    private boolean waitForTunnelHandshake() {
        sTunnelLatch = new java.util.concurrent.CountDownLatch(1);
        boolean ok = false;
        try { ok = sTunnelLatch.await(HS_TO, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        sTunnelLatch = null;
        return ok;
    }

    private void stopAll() {
        if (!run && !sRunning) { log("I stopAll: ya detenido, ignorado"); return; }
        stop = true;
        run = sRunning = sTunnelOk = false;

        stopHevStack();
        BtProxy.stop();
        unregisterNet();
        cleanupSessionResources();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        stop = false;
        log("I stopAll ok");
    }

    private void cleanupSessionResources() {
        tun     = null;
        hFd   = -1;
        hTh  = null;
        hCfg = null;
    }

    private boolean startHevStack() {
        ParcelFileDescriptor pfd = buildTunInterface();
        if (pfd == null) {
            log("E startHevStack: buildTunInterface failed");
            return false;
        }
        tun = pfd;

        int dupFd;
        try {
            dupFd = ParcelFileDescriptor.dup(tun.getFileDescriptor()).detachFd();
        } catch (Exception e) {
            log("E startHevStack: dup failed: " + e.getMessage());
            try { tun.close(); } catch (Exception ignored) {}
            tun   = null;
            hFd = -1;
            return false;
        }
        hFd = dupFd;

        startHevThread();
        return true;
    }

    private void stopHevStack() {
        try { HevBridge.stop(); } catch (Throwable ignored) {}

        Thread old = hTh;
        hTh = null;
        if (old != null) {
            try { old.join(3000); } catch (InterruptedException ignored) {}
            if (old.isAlive()) {
                log("W stopHevStack: hev thread no terminó en 3s");
                old.interrupt();
                try { old.join(1000); } catch (InterruptedException ignored) {}
            }
        }

        int fd = hFd;
        hFd = -1;
        if (fd >= 0) {
            try { ParcelFileDescriptor.adoptFd(fd).close(); } catch (Exception ignored) {}
        }

        ParcelFileDescriptor pfd = tun;
        tun = null;
        if (pfd != null) {
            try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    private void startHevThread() {
        hCfg = writeHevCfg();
        final int  fd  = hFd;
        final File cfg = hCfg;
        hTh = new Thread(() -> HevBridge.start(cfg.getAbsolutePath(), fd), "hev");
        hTh.setDaemon(true);
        hTh.start();
    }

    private ParcelFileDescriptor buildTunInterface() {
        try {
            Builder builder = new Builder()
                    .setSession("bt-hev")
                    .setMtu(1420)
                    .addAddress("198.18.0.1", 32)
                    .addAddress("fc00::1", 128)
                    .addDnsServer("8.8.8.8");

            addPublicRoutes(builder);

            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (Throwable t) {
                log("W addDisallowedApplication: " + t.getMessage());
            }

            ParcelFileDescriptor pfd = builder.establish();
            if (pfd == null) log("E establish returned null");
            return pfd;
        } catch (Throwable e) {
            log("E buildTunInterface: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private void registerNet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return;
            Network active = cm.getActiveNetwork();
            if (active != null) {
                BtProxy.nativeSetNetwork(active.getNetworkHandle());
                setUnderlyingNetworks(new Network[]{active});
            }
            nCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network net) {
                    BtProxy.nativeSetNetwork(net.getNetworkHandle());
                    setUnderlyingNetworks(new Network[]{net});
                }
                @Override public void onLost(Network net) {
                    BtProxy.nativeSetNetwork(0L);
                    setUnderlyingNetworks(null);
                }
            };
            cm.registerNetworkCallback(
                new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                nCb);
        } catch (Exception ignored) {}
    }

    private void unregisterNet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            ConnectivityManager.NetworkCallback cb = nCb;
            nCb = null;
            if (cm != null && cb != null) cm.unregisterNetworkCallback(cb);
        } catch (Exception ignored) {}
        BtProxy.nativeSetNetwork(0L);
    }

    private void addPublicRoutes(Builder builder) {
        String[] excludes = {
            "0.0.0.0/8",
            "10.0.0.0/8",
            "100.64.0.0/10",
            "127.0.0.0/8",
            "169.254.0.0/16",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "198.18.0.0/15",
            "224.0.0.0/4",
            "240.0.0.0/4",
            "255.255.255.255/32"
        };

        List<long[]> excluded = new ArrayList<>();
        for (String cidr : excludes) {
            try {
                String[] p = cidr.split("/");
                long base = ip2long(InetAddress.getByName(p[0]));
                int pfx = Integer.parseInt(p[1]);
                long mask = pfx == 0 ? 0L : (~0L << (32 - pfx)) & 0xFFFFFFFFL;
                long start = base & mask;
                excluded.add(new long[]{start, start + (~mask & 0xFFFFFFFFL)});
            } catch (UnknownHostException ignored) {}
        }
        excluded.sort((a, b) -> Long.compare(a[0], b[0]));

        long cur = 1L;
        for (long[] ex : excluded) {
            if (cur < ex[0]) addCIDRs(builder, cur, ex[0] - 1);
            if (cur <= ex[1]) cur = ex[1] + 1;
        }
        if (cur <= 0xFFFFFFFEL) addCIDRs(builder, cur, 0xFFFFFFFEL);

        builder.addRoute("2000::", 3);
    }

    private void addCIDRs(Builder builder, long start, long end) {
        while (start <= end) {
            int prefix = maxPrefix(start, end);
            builder.addRoute(long2ip(start), prefix);
            start += (1L << (32 - prefix));
        }
    }

    private int maxPrefix(long start, long end) {
        int p = Math.max(0, 32 - Math.min(32, Long.numberOfTrailingZeros(start)));
        while (p < 32 && (1L << (32 - p)) > (end - start + 1)) p++;
        return p;
    }

    private long ip2long(InetAddress a) {
        byte[] b = a.getAddress();
        return ((long)(b[0]&0xFF)<<24)|((long)(b[1]&0xFF)<<16)|((long)(b[2]&0xFF)<<8)|(b[3]&0xFF);
    }

    private String long2ip(long v) {
        return ((v>>24)&0xFF)+"."+((v>>16)&0xFF)+"."+((v>>8)&0xFF)+"."+(v&0xFF);
    }

    private File writeHevCfg() {
        String yml = """
            tunnel:
              name: bt-hev
              mtu: 1420
              ipv4: 198.18.0.1
              ipv6: 'fc00::1'
            socks5:
              address: 127.0.0.1
              port: %d
              udp: 'tcp'
              pipeline: false
            mapdns:
              address: 198.18.0.2
              port: 53
              network: 198.18.0.0
              netmask: 255.254.0.0
              cache-size: 10000
            misc:
              task-stack-size: 86016
              tcp-buffer-size: 65536
              connect-timeout: 10000
              read-write-timeout: 300000
              tcp-read-write-timeout: 3600000
              max-session-count: 4096
              limit-nofile: 65535
              log-file: stderr
              log-level: warn
            """.formatted(BtProxy.SOCKS5_PORT);
        File f = new File(getFilesDir(), "hev.yml");
        try (FileOutputStream o = new FileOutputStream(f, false)) {
            o.write(yml.getBytes(StandardCharsets.UTF_8));
            o.flush();
        } catch (Exception ignored) {}
        return f;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null)
            nm.createNotificationChannel(
                new NotificationChannel(CH_ID, "BlackTunnel", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification buildNotif() {
        return new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("BlackTunnel")
                .setOngoing(true)
                .build();
    }

    static final class HevBridge {
        static { System.loadLibrary("hev-jni"); }
        static native int  start(String path, int fd);
        static native void stop();
    }
}

final class BtProxy {

    static final int SOCKS5_PORT = 10809;

    private static final String PREFS           = "strike_prefs";
    private static final String KEY_INTERNAL_ID = "internal_id";

    private static boolean NATIVE_READY = false;

    static java.util.function.Consumer<String> logListener;
    static java.util.function.Consumer<String> stateListener;

    static {
        try {
            System.loadLibrary("btproxy");
            NATIVE_READY = true;
        } catch (Throwable t) {
            android.util.Log.e("BtProxy", "Failed to load btproxy", t);
        }
    }

    static boolean isNativeReady() { return NATIVE_READY; }

    static int start(VpnService svc, String id) {
        if (!NATIVE_READY) return -1;
        return nativeStart(SOCKS5_PORT, svc, id);
    }

    static void stop() {
        if (!NATIVE_READY) return;
        nativeStop();
    }

    static void setLogListener(java.util.function.Consumer<String> l) {
        logListener = l;
    }

    static void setStateListener(java.util.function.Consumer<String> l) {
        stateListener = l;
    }

    static void doRegisterCallbacks() {
        if (!NATIVE_READY) return;
        try {
            nativeSetCallback(BtVpnService.class, "onLog");
            nativeSetStateCallback(BtVpnService.class, "onStateChange");
        } catch (Throwable t) {
            android.util.Log.e("BtProxy", "Failed to register callbacks", t);
        }
    }

    static String gIid(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String existing = sp.getString(KEY_INTERNAL_ID, null);
        if (existing != null && !existing.isBlank()) return existing;

        String rawId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (rawId == null || rawId.isBlank()) rawId = "unknown";
        String seed = rawId + "|" + Build.BRAND + "|" + Build.MODEL + "|" +
                      ctx.getPackageName() + "|" + System.currentTimeMillis();
        String id = "S-" + sha256(seed).substring(0, 8).toUpperCase(Locale.ROOT);
        sp.edit().putString(KEY_INTERNAL_ID, id).apply();
        return id;
    }

    private static String sha256(String v) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                                    .digest(v.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Long.toHexString(System.currentTimeMillis()) +
                   Long.toHexString(System.nanoTime());
        }
    }

    private static native int    nativeStart(int port, VpnService svc, String id);
    private static native void   nativeStop();
    public  static native void   nativeSetNetwork(long networkHandle);
    private static native void   nativeSetCallback(Class clazz, String methodName);
    private static native void   nativeSetStateCallback(Class clazz, String methodName);
}
