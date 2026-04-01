package com.blacktunnel

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.IntentFilter
import android.app.PendingIntent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

class BtVpnService : VpnService() {

    private var pfd: ParcelFileDescriptor? = null
    private var rawTunFd: Int? = null
    @Volatile private var isStopping = false
    @Volatile private var desiredRunning = false
    private var networkReceiverRegistered = false
    private val networkChangeReceiver = NetworkChangeReceiver()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                desiredRunning = false
                TunnelPrefs.setWasConnected(this, false)
                stopTunnel()
                START_NOT_STICKY
            }
            else -> {
                desiredRunning = true
                TunnelPrefs.setWasConnected(this, true)
                startTunnel()
                START_STICKY
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!desiredRunning) return
        val restartIntent = Intent(applicationContext, BtVpnService::class.java).setAction(ACTION_START)
        val pending = PendingIntent.getService(
            this, 1, restartIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1000, pending)
    }

    override fun onDestroy() {
        if (desiredRunning) {
            val restartIntent = Intent(applicationContext, BtVpnService::class.java).setAction(ACTION_START)
            val pending = PendingIntent.getService(
                this, 2, restartIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
            alarm.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 2000,
                pending
            )
        }
        stopTunnel()
        super.onDestroy()
    }

    private fun startTunnel() {
        isStopping = false

        if (pfd != null) {
            TunnelSessionStore.setState("CONNECTED")
            return
        }

        TunnelSessionStore.setState("CONNECTING")
        startVpnForeground()

        thread(isDaemon = true, name = "vpn-start-sequence") {
            val clientId = TunnelPrefs.getOrCreateClientId(this)

            val builder = Builder()
                .setSession("XTunnel")
                .addAddress("198.18.0.1", 30)
                .addAddress("fc00::1", 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .addDnsServer("2001:4860:4860::8888")
                .addDnsServer("2606:4700:4700::1111")
                .setMtu(1300)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.allowFamily(OsConstants.AF_INET)
                builder.allowFamily(OsConstants.AF_INET6)
            }

            configureAllowedApplications(builder)

            val established = runCatching { builder.establish() }.getOrNull()
            if (established == null) {
                TunnelSessionStore.setState("ERROR")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@thread
            }

            pfd = established
            registerNetworkReceiver()

            BtProxy.start(
                ctx = this,
                clientId = clientId,
                protectSocket = { socket -> protect(socket) }
            )

            val rawFd = ParcelFileDescriptor.dup(established.fileDescriptor).detachFd()
            rawTunFd = rawFd
            val configFile = writeHevConfig()

            thread(isDaemon = true, name = "hev-main") {
                runCatching { HevBridge.start(configFile.absolutePath, rawFd) }
                closeRawTunFd()
            }
        }
    }

    private fun startVpnForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(VPN_CHANNEL_ID, "XTunnel VPN", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Servicio VPN activo"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = PendingIntent.getService(
            this, 0, Intent(this, BtVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, VPN_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("XTunnel activo")
            .setContentText("Conexión protegida")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Desconectar", disconnectIntent)
            .build()
        startForeground(VPN_NOTIFICATION_ID, notification)
    }

    private fun stopTunnel() {
        if (isStopping) return
        isStopping = true
        runCatching { HevBridge.stop() }
        BtProxy.stop()
        closeRawTunFd()
        runCatching { pfd?.close() }
        pfd = null
        runCatching {
            if (networkReceiverRegistered) {
                unregisterReceiver(networkChangeReceiver)
                networkReceiverRegistered = false
            }
        }
        TunnelSessionStore.setState("DISCONNECTED")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun registerNetworkReceiver() {
        if (networkReceiverRegistered) return
        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        registerReceiver(networkChangeReceiver, filter)
        networkReceiverRegistered = true
    }

    private fun closeRawTunFd() {
        val fd = rawTunFd ?: return
        rawTunFd = null
        runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
    }

    private fun configureAllowedApplications(builder: Builder) {
        val profile = TunnelPrefs.getProfile(this)
        val includedApps = TunnelPrefs.getIncludedApps(this)

        if (!profile.equals("performance", ignoreCase = true)) {
            runCatching { builder.addDisallowedApplication(packageName) }
            return
        }

        if (includedApps.isEmpty()) {
            packageManager.getInstalledApplications(0)
                .map { it.packageName }
                .filter { it != packageName }
                .forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
            return
        }

        includedApps.forEach { pkg -> runCatching { builder.addAllowedApplication(pkg) } }
    }

    private fun writeHevConfig(): java.io.File {
        val file = java.io.File(filesDir, "hev.yml")
        file.writeText(
            """
            tunnel:
              name: trehev
              mtu: 1300
              ipv4: 198.18.0.1
              ipv6: fc00::1
            socks5:
              address: 127.0.0.1
              port: 10808
              udp: 'udp'
            misc:
              log-level: warn
            """.trimIndent()
        )
        return file
    }

    companion object {
        const val ACTION_START = "com.blacktunnel.START"
        const val ACTION_STOP = "com.blacktunnel.STOP"
        private const val VPN_CHANNEL_ID = "vpn_channel"
        private const val VPN_NOTIFICATION_ID = 1
    }
}
