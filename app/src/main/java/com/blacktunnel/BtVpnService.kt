package com.blacktunnel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import kotlin.concurrent.thread

class BtVpnService : VpnService() {

    private var pfd: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        installCrashHandler()
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            else -> startTunnel()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler { crashThread, throwable ->
            LogStore.add("CRASH en $crashThread: ${throwable.message}")
            Log.e("BlackTunnel", "CRASH", throwable)
            runCatching {
                val dir = getExternalFilesDir(null) ?: return@runCatching
                val logFile = File(dir, "crash.log")
                logFile.appendText("${System.currentTimeMillis()} CRASH: ${throwable.stackTraceToString()}\n")
            }
        }
    }

    private fun startTunnel() {
        if (pfd != null) {
            LogStore.add("VPN already running, ignoring duplicated start")
            TunnelSessionStore.setState("CONNECTED")
            return
        }

        TunnelSessionStore.setState("CONNECTING")
        startVpnForeground()
        thread(isDaemon = true, name = "vpn-start-sequence") {
            val mtu = TunnelPrefs.getMtu(this).coerceIn(1200, 9000)
            val mux = TunnelPrefs.getMux(this).coerceIn(1, 64)
            val profile = TunnelPrefs.getProfile(this)
            val clientId = TunnelPrefs.getOrCreateClientId(this)

            val access = BtProxy.checkAccess(
                clientId = clientId,
                protectSocket = { socket -> protect(socket) },
                logger = { LogStore.add(it) }
            )
            TunnelSessionStore.updateFromHeaders(
                mapOf(
                    "X-Status" to access.state,
                    "X-Days-Left" to access.daysLeft,
                    "X-Name" to access.name,
                    "X-Expire" to "-",
                    "X-Premium" to "-"
                )
            )
            if (!access.isValid) {
                LogStore.add("Acceso no válido state=${access.state}")
                TunnelSessionStore.setState("ERROR")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@thread
            }

            BtProxy.start(
                ctx = this,
                mux = mux,
                profile = profile,
                clientId = clientId,
                protectSocket = { socket -> protect(socket) },
                logger = { LogStore.add(it) }
            )
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

            val established = runCatching { builder.establish() }.getOrElse {
                LogStore.add("VPN establish failed: ${it.message}")
                TunnelSessionStore.setState("ERROR")
                BtProxy.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@thread
            }

            if (established == null) {
                LogStore.add("VPN establish returned null")
                TunnelSessionStore.setState("ERROR")
                BtProxy.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@thread
            }

            pfd = established
            val rawFd = ParcelFileDescriptor.dup(established.fileDescriptor).detachFd()
            LogStore.add("TUN established fd=$rawFd")

            val configFile = writeHevConfig()
            thread(isDaemon = true, name = "hev-main") {
                val result = runCatching { HevBridge.start(configFile.absolutePath, rawFd) }.getOrElse {
                    LogStore.add("HEV crashed: ${it.message}")
                    TunnelSessionStore.setState("ERROR")
                    -1
                }
                LogStore.add("HEV terminó con code=$result")
                if (result != 0) {
                    TunnelSessionStore.setState("ERROR")
                }
            }
        }
    }

    private fun startVpnForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                VPN_CHANNEL_ID,
                "BlackTunnel VPN",
                NotificationManager.IMPORTANCE_MIN
            )
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
        runCatching { HevBridge.stop() }
        BtProxy.stop()
        if (pfd == null) {
            TunnelSessionStore.setState("DISCONNECTED")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        LogStore.add("Stopping VPN/HEV")
        runCatching { pfd?.close() }
        pfd = null
        TunnelSessionStore.setState("DISCONNECTED")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun configureAllowedApplications(builder: Builder) {
        val profile = TunnelPrefs.getProfile(this)
        val blockNonSelected = TunnelPrefs.isBlockNonSelectedEnabled(this)

        if (!profile.equals("performance", ignoreCase = true)) {
            runCatching { builder.addDisallowedApplication(packageName) }
                .onFailure { LogStore.add("WARN no se pudo excluir app propia: ${it.message}") }
            LogStore.add("Modo normal: todas las apps usan túnel")
            return
        }

        val includedApps = TunnelPrefs.getIncludedApps(this)
        if (includedApps.isEmpty()) {
            val installedPackages = packageManager.getInstalledApplications(0)
                .map { it.packageName }
                .filter { it != packageName }

            installedPackages.forEach { pkg ->
                runCatching { builder.addDisallowedApplication(pkg) }
                    .onFailure { LogStore.add("WARN no se pudo excluir $pkg: ${it.message}") }
            }
            LogStore.add("Modo performance: sin apps seleccionadas, tráfico por túnel desactivado")
            return
        }

        includedApps.forEach { pkg ->
            runCatching { builder.addAllowedApplication(pkg) }
                .onFailure { LogStore.add("WARN app incluida inválida $pkg: ${it.message}") }
        }

        if (blockNonSelected) {
            LogStore.add("Modo performance estricto: requiere bloqueo VPN del sistema para negar apps no seleccionadas")
        }
        LogStore.add("Modo performance: apps en túnel=${includedApps.joinToString()}")
    }


    private fun writeHevConfig(): java.io.File {
        val file = java.io.File(filesDir, "hev.yml")
        val yaml = """
            tunnel:
              name: trehev
              mtu: ${TunnelPrefs.getMtu(this).coerceIn(1200, 9000)}
              ipv4: 198.18.0.1
              ipv6: fc00::1
            socks5:
              address: 127.0.0.1
              port: 10808
              udp: 'udp'
            misc:
              log-level: warn
        """.trimIndent()
        file.writeText(yaml)
        return file
    }

    companion object {
        const val ACTION_START = "com.blacktunnel.START"
        const val ACTION_STOP = "com.blacktunnel.STOP"
        private const val VPN_CHANNEL_ID = "vpn_channel"
        private const val VPN_NOTIFICATION_ID = 1
    }
}
