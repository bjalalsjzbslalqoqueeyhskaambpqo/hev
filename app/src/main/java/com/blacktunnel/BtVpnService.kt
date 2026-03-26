package com.blacktunnel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
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
            LogStore.add("VPN already running")
            TunnelSessionStore.setState("CONNECTED")
            return
        }

        TunnelSessionStore.setState("CONNECTING")
        startVpnForeground()
        val mtu = TunnelPrefs.getMtu(this).coerceIn(1200, 9000)
        val mux = TunnelPrefs.getMux(this).coerceIn(1, 64)
        BtProxy.start(
            ctx = this,
            mux = mux,
            protectSocket = { socket -> protect(socket) },
            logger = { LogStore.add(it) }
        )
        thread(isDaemon = true, name = "proxy-ready-check") {
            val deadline = System.currentTimeMillis() + 5000
            var proxyReady = false
            while (System.currentTimeMillis() < deadline) {
                try {
                    java.net.Socket("127.0.0.1", 10808).close()
                    proxyReady = true
                    LogStore.add("BtProxy listo en 10808")
                    break
                } catch (_: Exception) {
                    Thread.sleep(100)
                }
            }
            if (!proxyReady) {
                LogStore.add("WARN BtProxy no respondió en 5s, continuando igual")
            }
        }

        val builder = Builder()
            .setSession("BlackTunnel")
            .addAddress("198.18.0.1", 30)
            .addAddress("fc00::1", 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("8.8.8.8")
            .setMtu(mtu)
        runCatching { builder.addDisallowedApplication(packageName) }
            .onFailure { LogStore.add("ERROR addDisallowedApplication: ${it.message}") }

        val established = runCatching { builder.establish() }.getOrElse {
            LogStore.add("VPN establish failed: ${it.message}")
            TunnelSessionStore.setState("ERROR")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        if (established == null) {
            LogStore.add("VPN establish returned null")
            TunnelSessionStore.setState("ERROR")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        pfd = established
        TunnelSessionStore.setState("CONNECTED")
        val rawFd = established.detachFd()
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
        if (pfd == null) {
            TunnelSessionStore.setState("DISCONNECTED")
            BtProxy.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        LogStore.add("Stopping VPN/HEV")
        runCatching { HevBridge.stop() }
        runCatching { pfd?.close() }
        pfd = null
        TunnelSessionStore.setState("DISCONNECTED")
        BtProxy.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
