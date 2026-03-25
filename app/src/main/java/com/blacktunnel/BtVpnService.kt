package com.blacktunnel

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlin.concurrent.thread

class BtVpnService : VpnService() {

    private var pfd: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

    private fun startTunnel() {
        if (pfd != null) {
            LogStore.add("VPN already running")
            return
        }

        Socks5Mock.start()

        val builder = Builder()
            .setSession("BlackTunnel")
            .addAddress("198.18.0.1", 30)
            .addAddress("fc00::1", 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("8.8.8.8")
            .addDisallowedApplication(packageName)
            .setMtu(8500)

        val established = runCatching { builder.establish() }.getOrElse {
            LogStore.add("VPN establish failed: ${it.message}")
            stopSelf()
            return
        }

        if (established == null) {
            LogStore.add("VPN establish returned null")
            stopSelf()
            return
        }

        pfd = established
        val rawFd = established.detachFd()
        LogStore.add("TUN established fd=$rawFd")

        val configFile = writeHevConfig()
        thread(name = "hev-main", isDaemon = true) {
            LogStore.add("HEV start with ${configFile.absolutePath}")
            val rc = runCatching { HevBridge.start(configFile.absolutePath, rawFd) }.getOrElse {
                LogStore.add("HEV crashed: ${it.message}")
                -1
            }
            LogStore.add("HEV exited rc=$rc")
        }

        thread(name = "hev-stats", isDaemon = true) {
            while (pfd != null) {
                val s = runCatching { HevBridge.stats() }.getOrNull()
                if (s != null && s.size >= 4) {
                    LogStore.add("HEV stats txBytes=${s[1]} rxBytes=${s[3]}")
                }
                Thread.sleep(3_000)
            }
        }
    }

    private fun stopTunnel() {
        if (pfd == null) {
            return
        }
        LogStore.add("Stopping VPN/HEV")
        runCatching { HevBridge.stop() }
        runCatching { pfd?.close() }
        pfd = null
        Socks5Mock.stop()
        stopSelf()
    }

    private fun writeHevConfig(): java.io.File {
        val file = java.io.File(filesDir, "hev.yml")
        val yaml = """
            tunnel:
              name: trehev
              mtu: 8500
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
    }
}
