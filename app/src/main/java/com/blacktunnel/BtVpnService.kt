package com.blacktunnel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

class BtVpnService : VpnService() {

    private var pfd: ParcelFileDescriptor? = null
    @Volatile
    private var isStopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                START_NOT_STICKY
            }
            else -> {
                startTunnel()
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
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
            val mtu = 1300
            val builder = Builder()
                .setSession("BlackTunnel")
                .addAddress("198.18.0.1", 30)
                .addAddress("fc00::1", 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .addDnsServer("2001:4860:4860::8888")
                .addDnsServer("2606:4700:4700::1111")
                .setMtu(mtu)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.allowFamily(OsConstants.AF_INET)
                builder.allowFamily(OsConstants.AF_INET6)
            }

            configureAllowedApplications(builder)

            val established = runCatching { builder.establish() }.getOrNull()
            if (established == null) {
                TunnelSessionStore.setState("ERROR")
                BtProxy.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@thread
            }

            pfd = established
            BtProxy.start(
                ctx = this,
                clientId = clientId,
                protectSocket = { socket -> protect(socket) }
            )
            val rawFd = ParcelFileDescriptor.dup(established.fileDescriptor).detachFd()
            val configFile = writeHevConfig()

            thread(isDaemon = true, name = "hev-main") {
                val result = runCatching { HevBridge.start(configFile.absolutePath, rawFd) }.getOrDefault(-1)
                if (result != 0) TunnelSessionStore.setState("ERROR")
            }
        }
    }

    private fun startVpnForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(VPN_CHANNEL_ID, "BlackTunnel VPN", NotificationManager.IMPORTANCE_MIN)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, VPN_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("BlackTunnel activo")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        startForeground(VPN_NOTIFICATION_ID, notification)
    }

    private fun stopTunnel() {
        if (isStopping) return
        isStopping = true
        runCatching { HevBridge.stop() }
        BtProxy.stop()
        runCatching { pfd?.close() }
        pfd = null
        TunnelSessionStore.setState("DISCONNECTED")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
