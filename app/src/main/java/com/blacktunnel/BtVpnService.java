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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class BtVpnService extends VpnService {
    public static final String ACTION_START = "com.blacktunnel.START";
    public static final String ACTION_STOP  = "com.blacktunnel.STOP";
    private static final String CHANNEL_ID  = "simple_vpn";
    private static final int    NOTIF_ID    = 33;

    private ParcelFileDescriptor tunPfd;
    private Thread hevThread;
    private volatile boolean hevReady = false;

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
                .setMtu(1500)
                .addAddress("198.18.0.1", 30)
                .addAddress("fc00::1", 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("198.18.0.2")
                .addDnsServer("8.8.8.8");

        try {
            b.addDisallowedApplication(getPackageName());
        } catch (Exception e) {
            SimpleLog.i("addDisallowedApplication error: " + e.getMessage());
        }

        tunPfd = b.establish();
        if (tunPfd == null) {
            SimpleLog.i("No se pudo establecer TUN");
            return;
        }

        int rawFd;
        try {
            rawFd = ParcelFileDescriptor.dup(tunPfd.getFileDescriptor()).detachFd();
        } catch (Exception e) {
            SimpleLog.i("dup fd error: " + e.getMessage());
            return;
        }

        File cfg = writeHevConfig();

        CountDownLatch hevLatch = new CountDownLatch(1);

        hevThread = new Thread(() -> {
            SimpleLog.i("HEV arrancando...");
            hevLatch.countDown();
            int code = HevBridge.start(cfg.getAbsolutePath(), rawFd);
            SimpleLog.i("HEV terminó code=" + code);
            hevReady = false;
            try { ParcelFileDescriptor.adoptFd(rawFd).close(); } catch (Exception ignored) {}
        }, "hev-main");
        hevThread.start();

        try {
            hevLatch.await(500, TimeUnit.MILLISECONDS);
            Thread.sleep(300);
        } catch (InterruptedException ignored) {}

        hevReady = true;
        SimpleLog.i("HEV listo, iniciando proxy...");

        BtProxy.start(sock -> {
            // Reintentar protect hasta que el VPN esté listo
            for (int i = 0; i < 5; i++) {
                if (protect(sock)) return true;
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
            SimpleLog.i("WARN: protect() falló 5 veces");
            return false;
        });
        SimpleLog.i("Proxy iniciado");
    }

    private void stopAll() {
        BtProxy.stop();
        HevBridge.stop();
        hevReady = false;
        if (tunPfd != null) {
            try { tunPfd.close(); } catch (Exception ignored) {}
            tunPfd = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        SimpleLog.i("Servicio detenido");
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
                     "  log-level: warn\n";
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false);
            fos.write(yml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
}
