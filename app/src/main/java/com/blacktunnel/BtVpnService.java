package com.blacktunnel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class BtVpnService extends VpnService {

    public static final String ACTION_START = "com.blacktunnel.START";
    public static final String ACTION_STOP  = "com.blacktunnel.STOP";

    private static final String  CH_ID   = "bt_vpn";
    private static final int     NF_ID   = 33;
    private static final int     TUN_MTU = 1500;

    private static final AtomicBoolean sRunning = new AtomicBoolean(false);
    private static final Object        LOG_LOCK = new Object();
    private static final StringBuilder LOGS     = new StringBuilder(16384);
    private static final int           LOG_CAP  = 48_000;

    private final ExecutorService executor  = Executors.newSingleThreadExecutor();
    private volatile boolean              running   = false;
    private volatile ParcelFileDescriptor tunPfd    = null;
    private volatile int                  hevTunFd  = -1;
    private volatile Thread               hevThread = null;

    public static boolean isRunningState() { return sRunning.get(); }

    public static void log(String message) {
        String line = "[" + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "] " + message + "\n";
        synchronized (LOG_LOCK) {
            LOGS.append(line);
            if (LOGS.length() > LOG_CAP) LOGS.delete(0, LOGS.length() - LOG_CAP);
        }
    }

    public static String dumpLogs() {
        synchronized (LOG_LOCK) {
            String n = BtProxy.drainLogs();
            if (n != null && !n.isBlank()) LOGS.append(n);
            if (LOGS.length() > LOG_CAP) LOGS.delete(0, LOGS.length() - LOG_CAP);
            return LOGS.toString();
        }
    }

    public static void clearLogs() {
        synchronized (LOG_LOCK) { LOGS.setLength(0); }
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
        super.onDestroy();
    }

    private void startAll() {
        if (running) return;
        try {
            createChannel();
            startForeground(NF_ID, buildNotif());
            log("START");

            String id = BtProxy.getOrCreateInternalId(this);
            BtProxy.stop();

            boolean proxyOk = BtProxy.start(this, id) >= 0;
            boolean hevOk   = proxyOk && startHevStack();

            if (!proxyOk || !hevOk) {
                log("ERROR proxy=" + proxyOk + " hev=" + hevOk);
                safeShutdown();
                return;
            }

            running = true;
            sRunning.set(true);
            log("CONNECTED");

        } catch (Throwable t) {
            log("ERROR " + t.getClass().getSimpleName() + ": " + t.getMessage());
            safeShutdown();
        }
    }

    private void stopAll() { safeShutdown(); }

    private synchronized void safeShutdown() {
        if (!running && !sRunning.get() && hevThread == null && tunPfd == null && hevTunFd < 0) return;
        running = false;
        sRunning.set(false);
        log("DISCONNECTED");
        stopHevStack();
        BtProxy.stop();
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}
        try { stopSelf(); }                             catch (Throwable ignored) {}
    }

    private boolean startHevStack() {
        try {
            tunPfd = new Builder()
                    .setSession("bt-hev")
                    .setMtu(TUN_MTU)
                    .addAddress("198.18.0.1", 15)
                    .addAddress("fc00::1", 128)
                    .addDnsServer("198.18.0.2")
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .establish();

            if (tunPfd == null) {
                log("ERROR hev: tun null");
                return false;
            }

            hevTunFd = ParcelFileDescriptor.dup(tunPfd.getFileDescriptor()).detachFd();

            File cfg = writeHevCfg();
            hevThread = new Thread(() -> HevBridge.start(cfg.getAbsolutePath(), hevTunFd), "hev-tunnel");
            hevThread.setDaemon(true);
            hevThread.start();
            return true;

        } catch (Throwable t) {
            log("ERROR hev: " + t.getClass().getSimpleName());
            stopHevStack();
            return false;
        }
    }

    private void stopHevStack() {
        try { HevBridge.stop(); } catch (Throwable ignored) {}

        Thread t = hevThread;
        hevThread = null;
        if (t != null) try { t.join(2000); } catch (InterruptedException ignored) {}

        ParcelFileDescriptor pfd = tunPfd;
        tunPfd = null;
        if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}

        hevTunFd = -1;
    }

    private File writeHevCfg() {
        String yml =
                "tunnel:\n" +
                "  name: bt-hev\n" +
                "  mtu: " + TUN_MTU + "\n" +
                "  ipv4: 198.18.0.1\n" +
                "  ipv6: 'fc00::1'\n" +
                "socks5:\n" +
                "  address: 127.0.0.1\n" +
                "  port: " + BtProxy.SOCKS5_PORT + "\n" +
                "  udp: 'tcp'\n" +
                "  pipeline: false\n" +
                "mapdns:\n" +
                "  address: 198.18.0.2\n" +
                "  port: 53\n";

        File f = new File(getFilesDir(), "hev.yml");
        try (FileOutputStream o = new FileOutputStream(f, false)) {
            o.write(yml.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log("ERROR cfg: " + e.getMessage());
        }
        return f;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CH_ID, "BlackTunnel", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotif() {
        return new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("BlackTunnel")
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    static final class HevBridge {
        static { System.loadLibrary("hev-jni"); }
        static native int  start(String cfgPath, int tunFd);
        static native void stop();
    }
}

final class BtProxy {
    static final int SOCKS5_PORT = 10809;

    private static final String PREFS           = "strike_prefs";
    private static final String KEY_INTERNAL_ID = "internal_id";

    static { System.loadLibrary("btproxy"); }

    static int    start(VpnService svc, String id) { return nativeStart(SOCKS5_PORT, svc, id); }
    static void   stop()                           { nativeStop(); }
    static String drainLogs()                      { return nativeDrainLogs(); }

    static String getOrCreateInternalId(Context ctx) {
        String id = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                       .getString(KEY_INTERNAL_ID, "");
        if (id != null && !id.isBlank()) return id;
        id = "STRK-" + Long.toHexString(System.currentTimeMillis()) + Long.toHexString(System.nanoTime());
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putString(KEY_INTERNAL_ID, id).apply();
        return id;
    }

    private static native int    nativeStart(int port, VpnService svc, String id);
    private static native void   nativeStop();
    private static native String nativeDrainLogs();
    public  static native void   nativeSetGamingMode(boolean enabled);
    public  static native void   nativeSetNetwork(long networkHandle);
}
