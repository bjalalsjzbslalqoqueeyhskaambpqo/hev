package com.blacktunnel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.core.app.NotificationCompat;

import java.io.File;

public class BtVpnService extends VpnService {
    public static final String ACTION_START = "com.blacktunnel.START";
    public static final String ACTION_STOP = "com.blacktunnel.STOP";
    private static final String CHANNEL_ID = "simple_vpn";
    private static final int NOTIF_ID = 33;

    private ParcelFileDescriptor tunPfd;
    private Thread hevThread;

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
                .setMtu(1300)
                .addAddress("198.18.0.1", 30)
                .addAddress("fc00::1", 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1");
        try {
            b.addDisallowedApplication(getPackageName());
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            SimpleLog.i("No se pudo excluir la app del TUN: " + e.getMessage());
        }

        tunPfd = b.establish();
        if (tunPfd == null) {
            SimpleLog.i("No se pudo establecer TUN");
            return;
        }

        File cfg = writeHevConfig();
        BtProxy.start(sock -> protect(sock));
        SimpleLog.i("Proxy iniciado");

        hevThread = new Thread(() -> {
            int fd = tunPfd.detachFd();
            int code = HevBridge.start(cfg.getAbsolutePath(), fd);
            SimpleLog.i("HEV terminó code=" + code);
        }, "hev-main");
        hevThread.start();
    }

    private void stopAll() {
        BtProxy.stop();
        HevBridge.stop();
        if (tunPfd != null) {
            try { tunPfd.close(); } catch (Exception ignored) {}
            tunPfd = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        SimpleLog.i("Servicio detenido");
    }

    private File writeHevConfig() {
        File f = new File(getFilesDir(), "hev.yml");
        String yml = """
                tunnel:
                  name: simple-hev
                  mtu: 1300
                  ipv4: 198.18.0.1
                  ipv6: fc00::1
                socks5:
                  address: 127.0.0.1
                  port: 10808
                  udp: 'tcp'
                misc:
                  log-level: warn
                """;
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false);
            fos.write(yml.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.flush();
            fos.close();
        } catch (Exception e) {
            SimpleLog.i("Error escribiendo hev.yml: " + e.getMessage());
        }
        return f;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Simple VPN", NotificationManager.IMPORTANCE_LOW));
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
}
