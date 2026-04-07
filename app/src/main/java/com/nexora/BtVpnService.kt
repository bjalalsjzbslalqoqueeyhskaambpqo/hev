package com.nexora

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
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

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("BTCRASH", "Crash en ${thread.name}: ${throwable.message}")
            if (desiredRunning && !isStopping) {
                val restartIntent = Intent(applicationContext, BtVpnService::class.java)
                    .setAction(ACTION_START)
                val pending = PendingIntent.getService(
                    applicationContext, 99, restartIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
                alarm.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 3000,
                    pending
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return runCatching {
            when (intent?.action) {
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
        }.getOrElse {
            android.util.Log.e("BTCRASH", "onStartCommand crash: ${it.message}")
            START_STICKY
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching { super.onTaskRemoved(rootIntent) }
        if (!desiredRunning) return
        runCatching {
            val restartIntent = Intent(applicationContext, BtVpnService::class.java)
                .setAction(ACTION_START)
            val pending = PendingIntent.getService(
                this, 1, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
            alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000, pending)
        }
    }

    override fun onDestroy() {
        runCatching {
            if (desiredRunning) {
                val restartIntent = Intent(applicationContext, BtVpnService::class.java)
                    .setAction(ACTION_START)
                val pending = PendingIntent.getService(
                    this, 2, restartIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
                alarm.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 2000,
                    pending
                )
            }
        }
        runCatching { stopTunnel() }
        runCatching { super.onDestroy() }
    }

    private fun startTunnel() {
        runCatching {
            isStopping = false

            if (pfd != null) {
                // Si ya existe TUN, no forzar estado CONNECTED aquí.
                // El estado final lo publica BtProxy cuando el túnel real queda listo.
                return
            }

            TunnelSessionStore.setState("CONNECTING")
            startVpnForeground()

            thread(isDaemon = true, name = "vpn-start-sequence") {
                runCatching {
                    val clientId = TunnelPrefs.getOrCreateClientId(this@BtVpnService)

                    val builder = Builder()
                        .setSession("Nexora")
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
                        runCatching { builder.allowFamily(OsConstants.AF_INET) }
                        runCatching { builder.allowFamily(OsConstants.AF_INET6) }
                    }

                    runCatching { configureAllowedApplications(builder) }

                    val established = runCatching { builder.establish() }.getOrNull()
                    if (established == null) {
                        TunnelSessionStore.setState("ERROR")
                        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                        runCatching { stopSelf() }
                        return@runCatching
                    }

                    pfd = established
                    runCatching { registerNetworkReceiver() }

                    BtProxy.start(
                        ctx = this@BtVpnService,
                        clientId = clientId,
                        protectSocket = { socket -> runCatching { protect(socket) } }
                    )

                    val rawFd = runCatching {
                        ParcelFileDescriptor.dup(established.fileDescriptor).detachFd()
                    }.getOrNull()

                    if (rawFd == null) {
                        TunnelSessionStore.setState("ERROR")
                        return@runCatching
                    }

                    rawTunFd = rawFd
                    val configFile = runCatching { writeHevConfig() }.getOrNull() ?: return@runCatching

                    thread(isDaemon = true, name = "hev-main") {
                        runCatching { HevBridge.start(configFile.absolutePath, rawFd) }
                        runCatching { closeRawTunFd() }
                    }
                }.onFailure { e ->
                    android.util.Log.e("BTCRASH", "vpn-start-sequence crash: ${e.message}")
                    TunnelSessionStore.setState("ERROR")
                }
            }
        }.onFailure { e ->
            android.util.Log.e("BTCRASH", "startTunnel crash: ${e.message}")
        }
    }

    private fun startVpnForeground() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    VPN_CHANNEL_ID, "Nexora VPN",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Servicio VPN activo"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(channel)
            }
            val openAppIntent = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            val disconnectIntent = PendingIntent.getService(
                this, 0,
                Intent(this, BtVpnService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, VPN_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Nexora activo")
                .setContentText("Conexión protegida")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openAppIntent)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Desconectar", disconnectIntent
                )
                .build()
            startForeground(VPN_NOTIFICATION_ID, notification)
        }
    }

    private fun stopTunnel() {
        if (isStopping) return
        isStopping = true
        runCatching { HevBridge.stop() }
        runCatching { BtProxy.stop() }
        runCatching { closeRawTunFd() }
        runCatching { pfd?.close() }
        pfd = null
        runCatching {
            if (networkReceiverRegistered) {
                unregisterReceiver(networkChangeReceiver)
                networkReceiverRegistered = false
            }
        }
        runCatching { TunnelSessionStore.setState("DISCONNECTED") }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
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
        const val ACTION_START = "com.nexora.START"
        const val ACTION_STOP = "com.nexora.STOP"
        private const val VPN_CHANNEL_ID = "vpn_channel"
        private const val VPN_NOTIFICATION_ID = 1
    }
}
