package com.blacktunnel

import android.content.Context
import com.blacktunnel.mux.TunnelMux
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
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
                    TunnelMux.TunnelDialer {
                        openFakeWebsocket(clientId.trim(), protectSocket)
                    },
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

    private fun openFakeWebsocket(
        clientId: String,
        protectSocket: (Socket) -> Unit
    ): Socket {
        val socket = Socket()
        protectSocket(socket)
        socket.connect(InetSocketAddress(MUX_SERVER_HOST, MUX_SERVER_PORT), 15_000)
        socket.tcpNoDelay = true
        socket.keepAlive = true

        val out = socket.getOutputStream()
        val inp = socket.getInputStream()

        val p1 = buildString {
            append("GET $MUX_WS_PATH HTTP/1.1\\r\\n")
            append("Host: $MUX_SERVER_HOST\\r\\n")
            append("User-Agent: okhttp/4.12.0\\r\\n\\r\\n")
        }
        val p2 = buildString {
            append("- $MUX_WS_PATH HTTP/1.1\\r\\n")
            append("Host: 2.brawlpass.com.ar\\r\\n")
            append("Upgrade: websocket\\r\\n")
            append("Connection: Upgrade\\r\\n")
            append("Action: tunnel\\r\\n")
            append("X-Client-Id: $clientId\\r\\n\\r\\n")
        }

        out.write(p1.toByteArray())
        out.flush()
        Thread.sleep(10)
        out.write(p2.toByteArray())
        out.flush()

        val response = StringBuilder()
        var prev = -1
        while (true) {
            val c = inp.read()
            if (c == -1) throw java.io.IOException("conexión cerrada durante handshake fake")
            response.append(c.toChar())
            if (prev == '\n'.code && c == '\n'.code && response.contains("\\r\\n\\r\\n")) break
            prev = c
        }
        if (!response.contains("101")) {
            throw java.io.IOException("handshake fake rechazado")
        }
        socket.soTimeout = 0
        return socket
    }
}
