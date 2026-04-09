package com.blacktunnel

import android.content.Context
import com.blacktunnel.mux.TunnelMux
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

object BtProxy {

    private const val MUX_SERVER_HOST = "2.brawlpass.com.ar"
    private const val MUX_SERVER_PORT = 80
    private const val MUX_WS_PATH = "/"
    private const val SOCKS5_PORT = 10808
    private const val DNS_PORT = 5353

    @Volatile private var running = false
    @Volatile private var mux: TunnelMux? = null

    fun start(
        ctx: Context,
        clientId: String,
        protectSocket: (java.net.Socket) -> Unit
    ) {
        if (running) return
        running = true
        TunnelSessionStore.setState("CONNECTING")
        thread(isDaemon = true, name = "mux-start") {
            runCatching {
                val localMux = TunnelMux(
                    MUX_SERVER_HOST,
                    MUX_SERVER_PORT,
                    MUX_WS_PATH,
                    clientId.trim(),
                    TunnelMux.SocketProtector { socket -> protectSocket(socket) },
                    SOCKS5_PORT,
                    DNS_PORT
                )
                mux = localMux
                localMux.start()
                TunnelSessionStore.setState("CONNECTED")
            }.onFailure {
                TunnelSessionStore.setState("ERROR")
                running = false
            }
        }
    }

    fun stop() {
        running = false
        runCatching { mux?.stop() }
        mux = null
        TunnelSessionStore.reset()
    }

    fun clearDnsCache() {
        // DNS cache ahora vive dentro de TunnelMux.
    }

    fun getHotspotIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .flatMap { intf -> intf.inetAddresses.asSequence().map { intf.name.lowercase() to it } }
            .filter { (_, addr) -> addr is Inet4Address && !addr.isLoopbackAddress }
            .map { (name, addr) -> name to addr.hostAddress }
            .let { pairs ->
                pairs.firstOrNull { (name, ip) ->
                    (name.contains("ap") || name.contains("swlan") ||
                        name.contains("rndis") || name.contains("wlan")) && !ip.startsWith("127.")
                }?.second ?: pairs.firstOrNull()?.second
            }
    }.getOrNull()
}
