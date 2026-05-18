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
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class BtVpnService extends VpnService {

    public static final String ACTION_START = "com.blacktunnel.START";
    public static final String ACTION_STOP  = "com.blacktunnel.STOP";
    public static final String ACTION_APPLY = "com.blacktunnel.APPLY";

    private static final String CH_ID = "bt_vpn";
    private static final int    NF_ID = 33;

    private static volatile boolean sRunning = false;

    private static final Object        LOG_LOCK      = new Object();
    private static final StringBuilder LOGS          = new StringBuilder(8192);
    private static final int           MAX_LOG_CHARS = 24000;
    private static final long          HANDSHAKE_WAIT_TIMEOUT_MS = 12000L;
    private static final long          HANDSHAKE_POLL_MS = 250L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile boolean              running     = false;
    private volatile ParcelFileDescriptor tunPfd      = null;
    private volatile int                  hevTunFd    = -1;
    private volatile Thread               hevThread   = null;
    private volatile File                 hevCfgFile  = null;
    private volatile ConnectivityManager.NetworkCallback netCallback = null;

    public static boolean isRunningState() { return sRunning; }

    public static String getHotspotIp() {
        try {
            InetAddress ip = InetAddress.getByName("192.168.43.1");
            return ip.getHostAddress();
        } catch (UnknownHostException ignored) {
            return null;
        }
    }

    public static void startLocalProxy(int port) {
        log("I startLocalProxy port=" + port);
    }

    public static void stopLocalProxy() {
        log("I stopLocalProxy");
    }

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
            executor.execute(this::stopAll);
            return START_NOT_STICKY;
        }
        executor.execute(this::startAll);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        executor.execute(this::stopAll);
        executor.shutdown();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        super.onDestroy();
    }

    private void startAll() {
        if (running) {
            log("I startAll: ya corriendo, ignorado");
            return;
        }

        createChannel();
        startForeground(NF_ID, buildNotif());

        BtProxy.stop();

        cleanupSessionResources();

        String internalId  = BtProxy.getOrCreateInternalId(this);
        int    startResult = BtProxy.start(this, internalId);
        if (startResult < 0) {
            log("E startAll: btproxy start failed");
            stopForeground(STOP_FOREGROUND_REMOVE);
            return;
        }

        if (!waitForTunnelHandshake()) {
            log("E startAll: tunnel handshake timeout/failure");
            BtProxy.stop();
            stopForeground(STOP_FOREGROUND_REMOVE);
            return;
        }

        registerNet();

        if (!startHevStack()) {
            log("E startAll: startHevStack failed, deshaciendo");
            unregisterNet();
            BtProxy.stop();
            stopForeground(STOP_FOREGROUND_REMOVE);
            return;
        }

        running  = true;
        sRunning = true;
        log("I startAll ok");
    }

    private boolean waitForTunnelHandshake() {
        long deadline = System.currentTimeMillis() + HANDSHAKE_WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            String nativeLogs = BtProxy.drainLogs();
            if (nativeLogs != null && !nativeLogs.isBlank()) {
                synchronized (LOG_LOCK) {
                    LOGS.append(nativeLogs);
                    if (LOGS.length() > MAX_LOG_CHARS)
                        LOGS.delete(0, LOGS.length() - MAX_LOG_CHARS);
                }
                String[] lines = nativeLogs.split("\n");
                for (String line : lines) {
                    if (line == null) continue;
                    String lower = line.trim().toLowerCase(Locale.ROOT);
                    if (lower.isEmpty()) continue;
                    if (lower.contains("tunnel ok") ||
                        lower.contains("user_name=") ||
                        lower.contains("user_days=")) return true;
                    if (lower.contains("handshake failed") ||
                        lower.contains("proxy no responde") ||
                        lower.contains("connect failed") ||
                        lower.contains("not_registered") ||
                        lower.contains("expired")) return false;
                }
            }
            try { Thread.sleep(HANDSHAKE_POLL_MS); } catch (InterruptedException ignored) { return false; }
        }
        return false;
    }

    private void stopAll() {
        if (!running && !sRunning) {
            log("I stopAll: ya detenido, ignorado");
            return;
        }

        running  = false;
        sRunning = false;

        stopHevStack();

        BtProxy.stop();

        unregisterNet();

        cleanupSessionResources();

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        log("I stopAll ok");
    }

    private void cleanupSessionResources() {
        tunPfd     = null;
        hevTunFd   = -1;
        hevThread  = null;
        hevCfgFile = null;
    }

    private boolean startHevStack() {
        ParcelFileDescriptor pfd = buildTunInterface();
        if (pfd == null) {
            log("E startHevStack: buildTunInterface failed");
            return false;
        }
        tunPfd = pfd;

        int dupFd;
        try {
            dupFd = ParcelFileDescriptor.dup(tunPfd.getFileDescriptor()).detachFd();
        } catch (Exception e) {
            log("E startHevStack: dup failed: " + e.getMessage());
            try { tunPfd.close(); } catch (Exception ignored) {}
            tunPfd   = null;
            hevTunFd = -1;
            return false;
        }
        hevTunFd = dupFd;

        startHevThread();
        return true;
    }

    private void stopHevStack() {
        try { HevBridge.stop(); } catch (Throwable ignored) {}

        Thread old = hevThread;
        hevThread = null;
        if (old != null) {
            try { old.join(3000); } catch (InterruptedException ignored) {}
            if (old.isAlive()) {
                log("W stopHevStack: hev thread no terminó en 3s, forzando interrupción");
                old.interrupt();
                try { old.join(1000); } catch (InterruptedException ignored) {}
            }
        }

        int fd = hevTunFd;
        hevTunFd = -1;
        if (fd >= 0) {
            try { ParcelFileDescriptor.adoptFd(fd).close(); } catch (Exception ignored) {}
        }

        ParcelFileDescriptor pfd = tunPfd;
        tunPfd = null;
        if (pfd != null) {
            try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    private void startHevThread() {
        hevCfgFile = writeHevCfg();
        final int  fd  = hevTunFd;
        final File cfg = hevCfgFile;
        hevThread = new Thread(() -> HevBridge.start(cfg.getAbsolutePath(), fd), "hev");
        hevThread.setDaemon(true);
        hevThread.start();
    }

    private void closeTunPfd() {
        ParcelFileDescriptor pfd = tunPfd; tunPfd = null;
        if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
    }

    private ParcelFileDescriptor buildTunInterface() {
        Builder builder = new Builder()
                .setSession("bt-hev")
                .setMtu(1420)
                .addAddress("198.18.0.1", 15)
                .addAddress("fc00::1", 128)
                .addDnsServer("198.18.0.2");
        addPublicRoutes(builder);
        try { builder.addDisallowedApplication(getPackageName()); } catch (Exception ignored) {}
        return builder.establish();
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
            netCallback = new ConnectivityManager.NetworkCallback() {
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
                netCallback);
        } catch (Exception ignored) {}
    }

    private void unregisterNet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            ConnectivityManager.NetworkCallback cb = netCallback;
            netCallback = null;
            if (cm != null && cb != null) cm.unregisterNetworkCallback(cb);
        } catch (Exception ignored) {}
        BtProxy.nativeSetNetwork(0L);
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
                int  pfx  = Integer.parseInt(p[1]);
                long mask  = pfx == 0 ? 0L : (~0L << (32 - pfx)) & 0xFFFFFFFFL;
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
        builder.addRoute("fc00::", 7);
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
        String yml =
            "tunnel:\n  name: bt-hev\n  mtu: 1420\n  ipv4: 198.18.0.1\n  ipv6: 'fc00::1'\n" +
            "socks5:\n  address: 127.0.0.1\n  port: " + BtProxy.SOCKS5_PORT + "\n  udp: 'tcp'\n  pipeline: false\n" +
            "mapdns:\n  address: 198.18.0.2\n  port: 53\n  network: 198.18.0.0\n  netmask: 255.254.0.0\n  cache-size: 8192\n" +
            "misc:\n" +
            "  connect-timeout: 3000\n" +
            "  tcp-read-write-timeout: 1800000\n" +
            "  udp-read-write-timeout: 900000\n" +
            "  max-session-count: 4096\n" +
            "  log-level: warn\n" +
            "  limit-nofile: 65535\n";
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
    private static final String KEY_GAMING_MODE = "gaming_mode";
    private static final String KEY_GAMING_APPS = "gaming_selected_packages";

    private static final boolean NATIVE_READY;
    private static final String  NATIVE_LOAD_ERROR;

    static {
        boolean ready = false;
        String  error = "";
        try {
            System.loadLibrary("btproxy");
            ready = true;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            android.util.Log.e("BtProxy", "No se pudo cargar btproxy", t);
        }
        NATIVE_READY      = ready;
        NATIVE_LOAD_ERROR = error;
    }

    static boolean isNativeReady()      { return NATIVE_READY; }
    static String  getNativeLoadError() { return NATIVE_LOAD_ERROR; }

    static int start(VpnService svc, String id) {
        if (!NATIVE_READY) return -1;
        return nativeStart(SOCKS5_PORT, svc, id);
    }

    static void stop() {
        if (!NATIVE_READY) return;
        nativeStop();
    }

    static String drainLogs() {
        if (!NATIVE_READY) return "";
        return nativeDrainLogs();
    }

    static String getOrCreateInternalId(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String existing = sp.getString(KEY_INTERNAL_ID, null);
        if (existing != null && !existing.isBlank()) return existing;
        String rawId = Settings.Secure.getString(
                ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (rawId == null || rawId.isBlank()) rawId = "unknown";
        String seed = rawId + "|" + Build.BRAND + "|" + Build.MODEL + "|" +
                      ctx.getPackageName() + "|" + System.currentTimeMillis();
        String id = "STRK-" + sha256(seed).substring(0, 48);
        sp.edit().putString(KEY_INTERNAL_ID, id).apply();
        return id;
    }

    static void applyStoredGamingMode(Context ctx) {
        isGamingMode(ctx);
    }

    static boolean isGamingMode(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_GAMING_MODE, false);
    }

    static void setGamingMode(Context ctx, boolean enabled) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_GAMING_MODE, enabled).apply();
    }

    static List<String> getGamingSelectedPackages(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> raw = sp.getStringSet(KEY_GAMING_APPS, Collections.emptySet());
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(raw);
    }

    static void setGamingSelectedPackages(Context ctx, List<String> packages) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> value = packages == null ? new HashSet<>() : new HashSet<>(packages);
        sp.edit().putStringSet(KEY_GAMING_APPS, value).apply();
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
    private static native String nativeDrainLogs();
    public  static native void   nativeSetNetwork(long networkHandle);
}
