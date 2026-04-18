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
import android.os.SystemClock;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class BtVpnService extends VpnService {
    public static final String ACTION_START = "com.blacktunnel.START";
    public static final String ACTION_STOP  = "com.blacktunnel.STOP";
    public static final String ACTION_APPLY = "com.blacktunnel.APPLY";
    private static final String CH_ID = "bt_vpn";
    private static final int    NF_ID = 33;
    private static final AtomicBoolean runningState = new AtomicBoolean(false);
    private static final AtomicBoolean proxyStarted = new AtomicBoolean(false);
    private static final Object        LOG_LOCK     = new Object();
    private static final StringBuilder LOGS         = new StringBuilder(8192);
    private static final int           MAX_LOG_CHARS = 24000;

    private final AtomicBoolean  running  = new AtomicBoolean(false);
    private final AtomicBoolean  stopping = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile ParcelFileDescriptor tunPfd;
    private volatile Thread               hevThread;
    private volatile int                  hevTunFd  = -1;
    private volatile File                 hevCfgFile;
    private volatile ConnectivityManager.NetworkCallback netCallback;

    public static boolean isRunningState() { return runningState.get(); }

    public static void log(String message) {
        String line = "[" + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())
                    + "] " + message + "\n";
        synchronized (LOG_LOCK) {
            LOGS.append(line);
            if (LOGS.length() > MAX_LOG_CHARS)
                LOGS.delete(0, LOGS.length() - MAX_LOG_CHARS);
        }
    }

    public static String dumpLogs() {
        synchronized (LOG_LOCK) {
            String native_ = BtProxy.drainLogs();
            if (native_ != null && !native_.isBlank()) {
                LOGS.append(native_);
                if (LOGS.length() > MAX_LOG_CHARS)
                    LOGS.delete(0, LOGS.length() - MAX_LOG_CHARS);
            }
            return LOGS.toString();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            executor.execute(() -> stopAll());
            return START_NOT_STICKY;
        }
        if (ACTION_APPLY.equals(action)) {
            executor.execute(this::applyRuntimeChanges);
            return START_STICKY;
        }
        executor.execute(this::startAll);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopAll();
        executor.shutdown();
        super.onDestroy();
    }

    public void onTunnelReconnected() {
        if (!running.get() || stopping.get()) return;
        executor.execute(this::rebuildTunnel);
    }

    private void rebuildTunnel() {
        if (!running.get() || stopping.get()) return;

        try { HevBridge.stop(); } catch (Throwable ignored) {}
        Thread old = hevThread; hevThread = null;
        if (old != null) try { old.join(2000); } catch (InterruptedException ignored) {}

        ParcelFileDescriptor oldPfd = tunPfd; tunPfd = null;
        if (oldPfd != null) try { oldPfd.close(); } catch (Exception ignored) {}
        int oldFd = hevTunFd; hevTunFd = -1;
        if (oldFd >= 0) try { ParcelFileDescriptor.adoptFd(oldFd).close(); } catch (Exception ignored) {}

        boolean gaming = BtProxy.isGamingMode(this);
        Builder builder = new Builder()
                .setSession("bt-hev")
                .setMtu(1500)
                .addAddress("198.18.0.1", 15)
                .addAddress("fd40::1", 128)
                .addDnsServer("198.18.0.2");
        addPublicRoutes(builder);
        applyPerAppVpnPolicy(builder);

        tunPfd = builder.establish();
        if (tunPfd == null) { stopAll(); return; }

        try {
            hevTunFd = ParcelFileDescriptor.dup(tunPfd.getFileDescriptor()).detachFd();
        } catch (Exception e) {
            try { tunPfd.close(); } catch (Exception ignored) {}
            tunPfd = null;
            stopAll(); return;
        }

        hevCfgFile = writeHevCfg(gaming);
        final int fd = hevTunFd; final File cfg = hevCfgFile;
        hevThread = new Thread(() -> {
            HevBridge.start(cfg.getAbsolutePath(), fd);
            try { ParcelFileDescriptor.adoptFd(fd).close(); } catch (Exception ignored) {}
            if (hevTunFd == fd) hevTunFd = -1;
        }, "hev");
        hevThread.start();
    }

    private void startAll() {
        if (running.get() || stopping.get()) return;

        createChannel();
        startForeground(NF_ID, buildNotif());

        BtProxy.stop();
        proxyStarted.set(false);

        String internalId = BtProxy.getOrCreateInternalId(this);
        boolean gaming    = BtProxy.isGamingMode(this);

        if (BtProxy.start(this, internalId) < 0) {
            SystemClock.sleep(250);
            if (BtProxy.start(this, internalId) < 0) {
                log("E btproxy start failed");
                stopForeground(STOP_FOREGROUND_REMOVE);
                return;
            }
        }
        proxyStarted.set(true);
        BtProxy.applyStoredGamingMode(this);
        registerNet();

        Builder builder = new Builder()
                .setSession("bt-hev")
                .setMtu(1500)
                .addAddress("198.18.0.1", 15)
                .addAddress("fd40::1", 128)
                .addDnsServer("198.18.0.2");
        addPublicRoutes(builder);
        applyPerAppVpnPolicy(builder);

        tunPfd = builder.establish();
        if (tunPfd == null) {
            unregisterNet();
            if (proxyStarted.compareAndSet(true, false)) BtProxy.stop();
            stopForeground(STOP_FOREGROUND_REMOVE);
            return;
        }

        try {
            hevTunFd = ParcelFileDescriptor.dup(tunPfd.getFileDescriptor()).detachFd();
        } catch (Exception e) {
            try { tunPfd.close(); } catch (Exception ignored) {}
            tunPfd = null;
            unregisterNet();
            if (proxyStarted.compareAndSet(true, false)) BtProxy.stop();
            stopForeground(STOP_FOREGROUND_REMOVE);
            return;
        }

        hevCfgFile = writeHevCfg(gaming);
        final int fd = hevTunFd; final File cfg = hevCfgFile;
        hevThread = new Thread(() -> {
            HevBridge.start(cfg.getAbsolutePath(), fd);
            try { ParcelFileDescriptor.adoptFd(fd).close(); } catch (Exception ignored) {}
            if (hevTunFd == fd) hevTunFd = -1;
        }, "hev");
        hevThread.start();

        running.set(true);
        runningState.set(true);
        log("I startAll ok");
    }

    private void stopAll() {
        if (!stopping.compareAndSet(false, true)) return;
        try {
            running.set(false);
            runningState.set(false);

            try { HevBridge.stop(); } catch (Throwable ignored) {}
            Thread ht = hevThread; hevThread = null;
            if (ht != null) try { ht.join(2000); } catch (InterruptedException ignored) {}

            ParcelFileDescriptor pfd = tunPfd; tunPfd = null;
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
            int fd = hevTunFd; hevTunFd = -1;
            if (fd >= 0) try { ParcelFileDescriptor.adoptFd(fd).close(); } catch (Exception ignored) {}

            if (proxyStarted.compareAndSet(true, false)) BtProxy.stop();

            unregisterNet();
            stopForeground(STOP_FOREGROUND_REMOVE);
            log("I stopAll ok");
        } finally {
            stopping.set(false);
        }
    }

    private void applyRuntimeChanges() {
        if (!running.get() || stopping.get()) return;
        BtProxy.applyRuntimeMode(this);
        rebuildTunnel();
    }

    private void registerNet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return;
            Network active = cm.getActiveNetwork();
            if (active != null) BtProxy.nativeSetNetwork(active.getNetworkHandle());
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network net) {
                    BtProxy.nativeSetNetwork(net.getNetworkHandle());
                }
                @Override public void onLost(Network net) {
                    BtProxy.nativeSetNetwork(0L);
                }
            };
            cm.registerNetworkCallback(
                new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                netCallback);
        } catch (Exception ignored) {}
    }

    private void unregisterNet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            ConnectivityManager.NetworkCallback cb = netCallback; netCallback = null;
            if (cm != null && cb != null) cm.unregisterNetworkCallback(cb);
            BtProxy.nativeSetNetwork(0L);
        } catch (Exception ignored) {}
    }

    private void addPublicRoutes(Builder builder) {
        String[] excludes = {
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
            "169.254.0.0/16", "224.0.0.0/4", "240.0.0.0/4", "255.255.255.255/32"
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
        long cur = 0L;
        for (long[] ex : excluded) {
            if (cur < ex[0]) addCIDRs(builder, cur, ex[0] - 1);
            if (cur <= ex[1]) cur = ex[1] + 1;
        }
        if (cur <= 0xFFFFFFFEL) addCIDRs(builder, cur, 0xFFFFFFFEL);
        builder.addRoute("2000::", 3);
        builder.addRoute("fd40::", 128);
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

    private void applyPerAppVpnPolicy(Builder builder) {
        boolean gaming = BtProxy.isGamingMode(this);
        List<String> pkgs = BtProxy.getGamingSelectedPackages(this);
        if (gaming && !pkgs.isEmpty()) {
            for (String pkg : pkgs)
                if (pkg != null && !pkg.isBlank())
                    try { builder.addAllowedApplication(pkg); } catch (Exception ignored) {}
        } else {
            try { builder.addDisallowedApplication(getPackageName()); } catch (Exception ignored) {}
        }
    }

    private File writeHevCfg(boolean gaming) {
        String yml =
            "tunnel:\n  name: bt-hev\n  mtu: 1500\n  ipv4: 198.18.0.1\n  ipv6: 'fd40::1'\n" +
            "socks5:\n  address: 127.0.0.1\n  port: " + BtProxy.SOCKS5_PORT + "\n  udp: 'tcp'\n  pipeline: true\n" +
            "mapdns:\n  address: 198.18.0.2\n  port: 53\n  network: 198.18.0.0\n  netmask: 255.254.0.0\n  cache-size: 8192\n" +
            "misc:\n" +
            "  connect-timeout: 5000\n" +
            "  tcp-read-write-timeout: 180000\n" +
            "  udp-read-write-timeout: 30000\n" +
            "  max-session-count: 256\n" +
            "  log-level: warn\n" +
            "  limit-nofile: 65535\n";
        File f = new File(getFilesDir(), "hev.yml");
        try (FileOutputStream o = new FileOutputStream(f, false)) {
            o.write(yml.getBytes(StandardCharsets.UTF_8)); o.flush();
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
                .setContentTitle("BlackTunnel").setOngoing(true).build();
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
    private static final String KEY_GAMING_MODE = "gaming_mode";
    private static final String KEY_GAMING_APPS = "gaming_apps";

    static { System.loadLibrary("btproxy"); }

    static int    start(VpnService svc, String id) { return nativeStart(SOCKS5_PORT, svc, id); }
    static void   stop()                           { nativeStop(); }
    static String drainLogs()                      { return nativeDrainLogs(); }

    static void setGamingMode(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putBoolean(KEY_GAMING_MODE, enabled).apply();
        nativeSetGamingMode(enabled);
    }

    static boolean isGamingMode(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                  .getBoolean(KEY_GAMING_MODE, false);
    }

    static void applyStoredGamingMode(Context ctx) { nativeSetGamingMode(isGamingMode(ctx)); }
    static void applyRuntimeMode(Context ctx)      { nativeApplyMode(isGamingMode(ctx)); }

    static List<String> getGamingSelectedPackages(Context ctx) {
        Set<String> set = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                             .getStringSet(KEY_GAMING_APPS, new HashSet<>());
        return new ArrayList<>(set);
    }

    static void setGamingSelectedPackages(Context ctx, List<String> packages) {
        HashSet<String> clean = new HashSet<>();
        if (packages != null)
            for (String pkg : packages)
                if (pkg != null && !pkg.isBlank()) clean.add(pkg);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putStringSet(KEY_GAMING_APPS, clean).apply();
    }

    static String getOrCreateInternalId(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String existing = sp.getString(KEY_INTERNAL_ID, null);
        if (existing != null && !existing.isBlank()) return existing;
        String rawId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (rawId == null) rawId = "unknown";
        String seed = rawId + "|" + Build.BRAND + "|" + Build.MODEL + "|" +
                      ctx.getPackageName() + "|" + System.currentTimeMillis();
        String id = "STRK-" + sha256(seed).substring(0, 48);
        sp.edit().putString(KEY_INTERNAL_ID, id).apply();
        return id;
    }

    private static String sha256(String v) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Long.toHexString(System.currentTimeMillis()) + Long.toHexString(System.nanoTime());
        }
    }

    private static native int    nativeStart(int port, VpnService svc, String id);
    private static native void   nativeStop();
    private static native String nativeDrainLogs();
    public  static native void   nativeSetGamingMode(boolean enabled);
    public  static native void   nativeApplyMode(boolean enabled);
    public  static native int    nativeGetGamingMode();
    public  static native void   nativeSetNetwork(long networkHandle);
}
