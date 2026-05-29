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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class BtVpnService extends VpnService {

    public static final String ACTION_START = "com.blacktunnel.START";
    public static final String ACTION_STOP  = "com.blacktunnel.STOP";
    public static final String ACTION_APPLY = "com.blacktunnel.APPLY";

    private static final String CH_ID = "bt_vpn";
    private static final int    NF_ID = 33;

    private static volatile boolean sRunning = false;
    private static volatile boolean sStarting = false;

    private static final long          HS_TO = 40000L;
    private static final long          HEV_START_GRACE_MS = 900L;
    private static final int           HEV_PENDING = Integer.MIN_VALUE;

    private final ExecutorService ex = Executors.newSingleThreadExecutor();

    private volatile boolean              run     = false;
    private volatile boolean              stop    = false;
    private volatile ParcelFileDescriptor tun      = null;
    private volatile int                  hFd    = -1;
    private volatile Thread               hTh   = null;
    private volatile File                 hCfg  = null;
    private volatile ConnectivityManager.NetworkCallback nCb = null;
    private volatile boolean              hsReady = false;
    private volatile boolean              hsFailed = false;
    private volatile String               hsFailStage = "";
    private volatile CountDownLatch       hsLatch = null;

    public static boolean iRun() { return sRunning; }
    public static boolean iStarting() { return sStarting; }
    public static boolean iActive() { return sRunning || sStarting; }

    public interface TunnelEventListener {
        void onTunnelEvent(int type, String key, String value);
    }

    private static volatile TunnelEventListener sTunnelEventListener;

    public static void setTunnelEventListener(TunnelEventListener listener) {
        sTunnelEventListener = listener;
    }

    public static void clearTunnelEventListener(TunnelEventListener listener) {
        if (sTunnelEventListener == listener) sTunnelEventListener = null;
    }

    public void onTunnelEvent(int type, String key, String value) {
        String safeKey = key != null ? key : "";
        String safeValue = value != null ? value : "";
        updateHandshakeState(type, safeKey, safeValue);

        TunnelEventListener listener = sTunnelEventListener;
        if (listener != null) {
            try {
                listener.onTunnelEvent(type, safeKey, safeValue);
                return;
            } catch (Throwable ignored) {}
        }

        try {
            Intent intent = new Intent("com.blacktunnel.TUNNEL_EVENT");
            intent.setPackage(getPackageName());
            intent.putExtra("type", type);
            intent.putExtra("key", safeKey);
            intent.putExtra("value", safeValue);
            sendBroadcast(intent);
        } catch (Throwable ignored) {}
    }

    private void updateHandshakeState(int type, String key, String value) {
        if (type == 1 && "stage".equals(key)) {
            if ("relay_ready".equals(value)) {
                hsReady = true;
                hsFailed = false;
                hsFailStage = "";
                signalHandshakeWaiter();
            } else if ("auth_rejected".equals(value)
                    || "manual_reconnect_required".equals(value)) {
                hsFailed = true;
                hsFailStage = value;
                signalHandshakeWaiter();
            }
        } else if (type == 4) {
            hsFailed = true;
            hsFailStage = "auth_rejected";
            signalHandshakeWaiter();
        }
    }

    private void signalHandshakeWaiter() {
        CountDownLatch latch = hsLatch;
        if (latch != null) latch.countDown();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        ex.execute(() -> {
            try {
                if (ACTION_STOP.equals(action)) stopAll();
                else if (ACTION_APPLY.equals(action)) applyAll();
                else startAll();
            } catch (Throwable startFailure) {
                run = false;
                stop = false;
                sRunning = false;
                sStarting = false;
                try { stopHevStack(); } catch (Throwable ignored) {}
                try { BtProxy.stop(); } catch (Throwable ignored) {}
                try { unregisterNet(); } catch (Throwable ignored) {}
                try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}
                try { stopSelf(); } catch (Throwable ignored) {}
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


    private void applyAll() {
        if (!run && !sRunning) {
            startAll();
            return;
        }
        // La configuración de rutas de aplicaciones se aplica al crear la interfaz TUN;
        // para que el cambio tenga efecto sin dejar estados fantasma, reiniciamos el stack.
        stopAll();
        startAll();
    }

    private void startAll() {
        if (stop) {
            try { Thread.sleep(450); } catch (InterruptedException ignored) { return; }
            if (stop) {
                return;
            }
        }
        if (run || sRunning || sStarting) {
            return;
        }

        sStarting = true;
        createChannel();
        startForeground(NF_ID, buildNotif());

        BtProxy.stop();
        cleanupSessionResources();
        hsReady = false;
        hsFailed = false;
        hsFailStage = "";
        hsLatch = new CountDownLatch(1);

        registerNet();

        String iid  = BtProxy.gIid(this);
        onTunnelEvent(1, "stage", "native_start");
        int    startResult = BtProxy.start(this, iid);
        if (startResult < 0) {
            cleanupFailedStart();
            onTunnelEvent(1, "stage", "proxy_connect_failed");
            return;
        }

        if (!waitForTunnelHandshake()) {
            String failStage = hsFailed && !hsFailStage.isEmpty() ? hsFailStage : "proxy_connect_failed";
            BtProxy.stop();
            cleanupFailedStart();
            onTunnelEvent(1, "stage", failStage);
            return;
        }

        onTunnelEvent(1, "stage", "vpn_start");
        if (!startHevStack()) {
            BtProxy.stop();
            cleanupFailedStart();
            onTunnelEvent(1, "stage", "hev_failed");
            return;
        }

        run  = true;
        sRunning = true;
        sStarting = false;
        onTunnelEvent(1, "stage", "hev_started");
    }

    private void cleanupFailedStart() {
        run = false;
        sRunning = false;
        sStarting = false;
        hsReady = false;
        hsFailStage = "";
        hsLatch = null;
        stopHevStack();
        unregisterNet();
        cleanupSessionResources();
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}
        try { stopSelf(); } catch (Throwable ignored) {}
    }

    private boolean waitForTunnelHandshake() {
        CountDownLatch latch = hsLatch;
        if (hsReady) return true;
        if (hsFailed || latch == null) return false;
        try {
            latch.await(HS_TO, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        }
        return hsReady && !hsFailed;
    }

    private void stopAll() {
        if (!run && !sRunning && !sStarting) {
            return;
        }
        stop = true;

        run  = false;
        sRunning = false;
        sStarting = false;

        stopHevStack();
        BtProxy.stop();
        unregisterNet();
        cleanupSessionResources();

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        stop = false;
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
            return false;
        }
        tun = pfd;

        int dupFd;
        try {
            dupFd = ParcelFileDescriptor.dup(tun.getFileDescriptor()).detachFd();
        } catch (Exception dupFailure) {
            try { tun.close(); } catch (Exception closeFailure) {}
            tun   = null;
            hFd = -1;
            return false;
        }
        hFd = dupFd;

        if (!startHevThread()) {
            stopHevStack();
            return false;
        }
        return true;
    }

    private void stopHevStack() {
        try { HevBridge.stop(); } catch (Throwable ignored) {}

        Thread old = hTh;
        hTh = null;
        if (old != null) {
            try { old.join(3000); } catch (InterruptedException ignored) {}
            if (old.isAlive()) {
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

    private boolean startHevThread() {
        hCfg = writeHevCfg();
        if (hCfg == null || !hCfg.exists() || hCfg.length() == 0L) {
            return false;
        }

        final int  fd  = hFd;
        final File cfg = hCfg;
        final CountDownLatch exitedEarly = new CountDownLatch(1);
        final AtomicInteger result = new AtomicInteger(HEV_PENDING);

        Thread thread = new Thread(() -> {
            int rc = -1;
            try {
                rc = HevBridge.start(cfg.getAbsolutePath(), fd);
            } catch (Throwable ignored) {
            } finally {
                result.set(rc);
                exitedEarly.countDown();
                if (!stop && sRunning && hTh == Thread.currentThread()) {
                    onTunnelEvent(1, "stage", "hev_failed");
                }
            }
        }, "hev");
        hTh = thread;
        thread.setDaemon(true);
        thread.start();

        try {
            if (exitedEarly.await(HEV_START_GRACE_MS, TimeUnit.MILLISECONDS)) {
                hTh = null;
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        return true;
    }

    private ParcelFileDescriptor buildTunInterface() {
        Builder builder = new Builder()
                .setSession("bt-hev")
                .setMtu(1420)
                .addAddress("198.18.0.1", 32)
                .addAddress("fc00::1", 128)
                .addDnsServer("198.18.0.2")
                .addRoute("198.18.0.0", 15);

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
                try { setUnderlyingNetworks(new Network[]{active}); } catch (Exception ignored) {}
            }
            nCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network net) {
                    BtProxy.nativeSetNetwork(net.getNetworkHandle());
                    try { setUnderlyingNetworks(new Network[]{net}); } catch (Exception ignored) {}
                }
                @Override public void onLost(Network net) {
                    BtProxy.nativeSetNetwork(0L);
                    try { setUnderlyingNetworks(null); } catch (Exception ignored) {}
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
                int  pfx  = Integer.parseInt(p[1]);
                long mask  = pfx == 0 ? 0L : (~0L << (32 - pfx)) & 0xFFFFFFFFL;
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
        String yml =
            "tunnel:\n" +
            "  name: bt-hev\n" +
            "  mtu: 1420\n" +
            "  ipv4: 198.18.0.1\n" +
            "  ipv6: 'fc00::1'\n" +
            "socks5:\n" +
            "  address: 127.0.0.1\n" +
            "  port: " + BtProxy.SOCKS5_PORT + "\n" +
            "  udp: 'tcp'\n" +
            "  pipeline: false\n" +
            "mapdns:\n" +
            "  address: 198.18.0.2\n" +
            "  port: 53\n" +
            "  network: 198.18.0.0\n" +
            "  netmask: 255.254.0.0\n" +
            "  cache-size: 10000\n" +
            "misc:\n" +
            "  task-stack-size: 86016\n" +
            "  tcp-buffer-size: 65536\n" +
            "  connect-timeout: 10000\n" +
            "  read-write-timeout: 300000\n" +
            "  tcp-read-write-timeout: 3600000\n" +
            "  max-session-count: 4096\n" +
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
        static native long[] stats();
    }
}

final class BtProxy {

    static final int SOCKS5_PORT = 10809;

    private static final String PREFS           = "strike_prefs";
    private static final String KEY_INTERNAL_ID = "internal_id";
    private static final String KEY_GAMING_MODE = "gaming_mode";
    private static final String KEY_GAMING_APPS = "gaming_selected_packages";

    private static final boolean NATIVE_READY;

    static {
        boolean ready = false;
        try {
            System.loadLibrary("btproxy");
            ready = true;
        } catch (Throwable ignored) {}
        NATIVE_READY = ready;
    }

    static int start(VpnService svc, String id) {
        if (!NATIVE_READY) return -1;
        return nativeStart(SOCKS5_PORT, svc, id);
    }

    static void stop() {
        if (!NATIVE_READY) return;
        nativeStop();
    }


    static String gIid(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String existing = sp.getString(KEY_INTERNAL_ID, null);
        if (existing != null && !existing.isBlank()) return existing;
        String rawId = Settings.Secure.getString(
                ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (rawId == null || rawId.isBlank()) rawId = "unknown";
        String seed = rawId + "|" + Build.BRAND + "|" + Build.MODEL + "|" +
                      ctx.getPackageName() + "|" + System.currentTimeMillis();
        String id = "S-" + sha256(seed).substring(0, 8).toUpperCase(Locale.ROOT);
        sp.edit().putString(KEY_INTERNAL_ID, id).apply();
        return id;
    }

    static void aGm(Context ctx) {
        iGm(ctx);
    }

    static boolean iGm(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_GAMING_MODE, false);
    }

    static void sGm(Context ctx, boolean enabled) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_GAMING_MODE, enabled).apply();
    }

    static List<String> gGmPk(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> raw = sp.getStringSet(KEY_GAMING_APPS, Collections.emptySet());
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(raw);
    }

    static void sGmPk(Context ctx, List<String> packages) {
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
        } catch (Exception ignored) {
            return Long.toHexString(System.currentTimeMillis()) +
                   Long.toHexString(System.nanoTime());
        }
    }

    private static native int    nativeStart(int port, VpnService svc, String id);
    private static native void   nativeStop();
    public  static native void   nativeSetNetwork(long networkHandle);
}
