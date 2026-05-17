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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BtVpnService extends VpnService {
    public static final String ACTION_START = "com.blacktunnel.START";
    public static final String ACTION_STOP = "com.blacktunnel.STOP";

    private static final String CH_ID = "bt_vpn";
    private static final int NF_ID = 33;

    private static volatile boolean sRunning = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;
    private volatile ParcelFileDescriptor tunPfd;
    private volatile int hevTunFd = -1;
    private volatile Thread hevThread;

    public static boolean isRunningState() { return sRunning; }

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
            BtProxy.stop();
            if (BtProxy.start(this, BtProxy.getOrCreateInternalId(this)) < 0 || !startHevStack()) {
                safeShutdown();
                return;
            }
            running = true;
            sRunning = true;
        } catch (Throwable t) {
            safeShutdown();
        }
    }

    private void stopAll() {
        safeShutdown();
    }

    private void safeShutdown() {
        running = false;
        sRunning = false;
        try { stopHevStack(); } catch (Throwable ignored) {}
        try { BtProxy.stop(); } catch (Throwable ignored) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}
        try { stopSelf(); } catch (Throwable ignored) {}
    }

    private boolean startHevStack() {
        try {
            tunPfd = new Builder().setSession("bt-hev").setMtu(1380)
                    .addAddress("198.18.0.1", 15).addDnsServer("198.18.0.2")
                    .addRoute("0.0.0.0", 0).establish();
            if (tunPfd == null) return false;
            hevTunFd = ParcelFileDescriptor.dup(tunPfd.getFileDescriptor()).detachFd();
            File cfg = writeHevCfg();
            hevThread = new Thread(() -> HevBridge.start(cfg.getAbsolutePath(), hevTunFd), "hev");
            hevThread.start();
            return true;
        } catch (Throwable t) {
            try { stopHevStack(); } catch (Throwable ignored) {}
            return false;
        }
    }

    private void stopHevStack() {
        try { HevBridge.stop(); } catch (Throwable ignored) {}
        Thread t = hevThread; hevThread = null;
        if (t != null) try { t.join(1500); } catch (InterruptedException ignored) {}
        ParcelFileDescriptor pfd = tunPfd; tunPfd = null;
        if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        int fd = hevTunFd; hevTunFd = -1;
        if (fd >= 0) try { ParcelFileDescriptor.adoptFd(fd).close(); } catch (Exception ignored) {}
    }

    private File writeHevCfg() {
        String yml = "tunnel:\n  name: bt-hev\n  mtu: 1380\n  ipv4: 198.18.0.1\n" +
                "socks5:\n  address: 127.0.0.1\n  port: " + BtProxy.SOCKS5_PORT + "\n";
        File f = new File(getFilesDir(), "hev.yml");
        try (FileOutputStream o = new FileOutputStream(f, false)) { o.write(yml.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ignored) {}
        return f;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(CH_ID, "BlackTunnel", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification buildNotif() {
        return new NotificationCompat.Builder(this, CH_ID).setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("BlackTunnel").setOngoing(true).build();
    }

    static final class HevBridge {
        static { System.loadLibrary("hev-jni"); }
        static native int start(String path, int fd);
        static native void stop();
    }
}

final class BtProxy {
    static final int SOCKS5_PORT = 10809;
    private static final String PREFS = "strike_prefs";
    private static final String KEY_INTERNAL_ID = "internal_id";
    static { System.loadLibrary("btproxy"); }
    static int start(VpnService svc, String id) { return nativeStart(SOCKS5_PORT, svc, id); }
    static void stop() { nativeStop(); }
    static String getOrCreateInternalId(Context ctx) {
        String id = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_INTERNAL_ID, "");
        if (id != null && !id.isBlank()) return id;
        id = "STRK-" + Long.toHexString(System.currentTimeMillis()) + Long.toHexString(System.nanoTime());
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_INTERNAL_ID, id).apply();
        return id;
    }
    private static native int nativeStart(int port, VpnService svc, String id);
    private static native void nativeStop();
    private static native String nativeDrainLogs();
    public static native void nativeSetGamingMode(boolean enabled);
    public static native void nativeSetNetwork(long networkHandle);
}
