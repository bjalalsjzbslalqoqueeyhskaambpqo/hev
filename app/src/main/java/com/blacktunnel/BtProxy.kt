package com.blacktunnel

import android.content.Context
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

object BtProxy {

    private const val PROXY_IPV6 = "2606:4700::6812:16b7"
    private const val PROXY_HOST = "emailmarketing.personal.com.ar"
    private const val PROXY_PORT = 80
    private const val TUNNEL_HOST = "1.brawlpass.com.ar"
    private const val ACTION_TUNNEL = "tunnel"
    private const val HANDSHAKE_END = "\r\n\r\n"
    private const val XRAY_SOCKS5_PORT = 10808
    private const val TUNNEL_LOCAL_PORT = 10809
    private const val XUDP_CONCURRENCY = 80
    private const val TEST_UUID = "a3482e88-686a-4a58-8126-99c9df64b7bf"

    @Volatile private var running = false
    @Volatile private var currentClientId = ""
    @Volatile private var xrayProcess: Process? = null
    @Volatile private var bridgeServer: ServerSocket? = null

    fun start(
        ctx: Context,
        clientId: String,
        protectSocket: (Socket) -> Unit
    ) {
        currentClientId = clientId.trim()
        running = true
        thread(isDaemon = true, name = "btproxy-init") {
            startTunnelBridge(protectSocket)
            startXray(ctx)
        }
    }

    fun stop() {
        running = false
        runCatching { bridgeServer?.close() }
        bridgeServer = null
        xrayProcess?.let { process ->
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
        xrayProcess = null
        TunnelSessionStore.reset()
    }

    private fun startTunnelBridge(protectSocket: (Socket) -> Unit) {
        runCatching { bridgeServer?.close() }
        val server = ServerSocket(TUNNEL_LOCAL_PORT, 128, InetAddress.getByName("127.0.0.1"))
        bridgeServer = server

        thread(isDaemon = true, name = "bridge-accept") {
            try {
                while (running) {
                    val client = server.accept().also { it.tcpNoDelay = true }
                    thread(isDaemon = true, name = "bridge-conn") {
                        val tunnel = openTunnel(protectSocket)
                        if (tunnel == null) {
                            runCatching { client.close() }
                            return@thread
                        }
                        relay(client, tunnel)
                    }
                }
            } catch (_: Exception) {
                runCatching { server.close() }
                if (bridgeServer === server) bridgeServer = null
            }
        }
    }

    private fun relay(client: Socket, tunnel: Socket) {
        val buffer = ByteArray(65536)
        val upstream = thread(isDaemon = true) {
            runCatching {
                val clientIn = client.getInputStream()
                val tunnelOut = tunnel.getOutputStream()
                while (true) {
                    val read = clientIn.read(buffer)
                    if (read < 0) break
                    tunnelOut.write(buffer, 0, read)
                }
            }
            runCatching { tunnel.shutdownOutput() }
        }

        val downstream = thread(isDaemon = true) {
            runCatching {
                val tunnelIn = tunnel.getInputStream()
                val clientOut = client.getOutputStream()
                while (true) {
                    val read = tunnelIn.read(buffer)
                    if (read < 0) break
                    clientOut.write(buffer, 0, read)
                }
            }
            upstream.interrupt()
            runCatching { client.close() }
            runCatching { tunnel.close() }
        }

        upstream.join()
        downstream.join()
    }

    private fun startXray(ctx: Context) {
        runCatching {
            val binary = resolveXrayBinary(ctx) ?: return
            binary.setExecutable(true, false)
            if (!binary.canExecute()) return

            val config = File(ctx.filesDir, "xray-client.json").also { it.writeText(buildClientConfig(ctx)) }
            xrayProcess = ProcessBuilder(listOf(binary.absolutePath, "run", "-c", config.absolutePath))
                .directory(binary.parentFile ?: File(ctx.applicationInfo.nativeLibraryDir))
                .redirectErrorStream(true)
                .start()
        }
    }

    private fun resolveXrayBinary(ctx: Context): File? {
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val candidates = listOf(
            File(nativeDir, "libxray.so"),
            File(nativeDir, "xray"),
            File(ctx.filesDir, "libxray.so"),
            File(ctx.filesDir, "xray")
        )
        return candidates.firstOrNull { it.exists() }
    }

    private fun buildClientConfig(ctx: Context): String {
        val muxConcurrency = TunnelPrefs.getMux(ctx)
        return """
        {
          "log": { "loglevel": "none" },
          "policy": {
            "system": {
              "udpTimeout": 600,
              "connIdle": 600,
              "downlinkOnly": 30,
              "uplinkOnly": 30
            }
          },
          "inbounds": [
            {
              "protocol": "socks",
              "listen": "127.0.0.1",
              "port": $XRAY_SOCKS5_PORT,
              "settings": { "udp": true }
            }${buildHotspotInbound(ctx)}
          ],
          "outbounds": [
            {
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {
                    "address": "127.0.0.1",
                    "port": $TUNNEL_LOCAL_PORT,
                    "users": [{ "id": "$TEST_UUID", "encryption": "none" }]
                  }
                ]
              },
              "streamSettings": { "network": "tcp", "security": "none" },
              "mux": {
                "enabled": true,
                "concurrency": $muxConcurrency,
                "xudpConcurrency": $XUDP_CONCURRENCY,
                "xudpProxyUDP443": "allow"
              }
            }
          ]
        }
    """.trimIndent()
    }

    private fun buildHotspotInbound(ctx: Context): String {
        if (!TunnelPrefs.isHotspotProxyEnabled(ctx)) return ""
        val ip = getHotspotIp() ?: return ""
        return """,
            {
              "protocol": "socks",
              "listen": "0.0.0.0",
              "port": 1080,
              "settings": { "udp": true, "ip": "$ip" }
            }"""
    }

    private fun getHotspotIp(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .flatMap { intf -> intf.inetAddresses.asSequence().map { intf.name.lowercase() to it } }
            .filter { (_, addr) -> addr is java.net.Inet4Address && !addr.isLoopbackAddress }
            .map { (name, addr) -> name to addr.hostAddress }
            .let { pairs ->
                pairs.firstOrNull { (name, ip) ->
                    (name.contains("ap") || name.contains("swlan") ||
                        name.contains("rndis") || name.contains("wlan")) && !ip.startsWith("127.")
                }?.second ?: pairs.firstOrNull()?.second
            }
    }.getOrNull()

    private fun openTunnel(protectSocket: (Socket) -> Unit): Socket? {
        val result = establishTunnelSocket(protectSocket) ?: return null
        val socket = result.first
        val handshake = result.second

        val authState = handshake.headers["x-auth-state"] ?: handshake.headers["x-status"]
        if (!authState.isNullOrBlank() && !authState.equals("VALID", ignoreCase = true)) {
            runCatching { socket.close() }
            TunnelSessionStore.setState("ERROR")
            return null
        }

        TunnelSessionStore.updateFromHeaders(
            mapOf(
                "X-Status" to (authState ?: "-"),
                "X-Name" to (handshake.headers["x-name"] ?: "-"),
                "X-Days-Left" to (handshake.headers["x-days-left"] ?: "-")
            )
        )

        TunnelSessionStore.setLatency(handshake.latencyMs)
        TunnelSessionStore.setState("CONNECTED")
        return socket
    }

    private fun establishTunnelSocket(
        protectSocket: (Socket) -> Unit
    ): Pair<Socket, HandshakeResult>? {
        if (currentClientId.isBlank()) return null

        return try {
            val startMs = System.currentTimeMillis()
            val socket = openProxySocket(protectSocket) ?: return null
            socket.tcpNoDelay = true

            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(buildHandshakeRequest().toByteArray())
            out.flush()

            val response = readHandshakeResponse(input, socket)
            val handshake = parseTunnelHandshake(response)?.copy(latencyMs = System.currentTimeMillis() - startMs)
            if (handshake == null) {
                runCatching { socket.close() }
                return null
            }
            socket.soTimeout = 0
            socket to handshake
        } catch (_: Exception) {
            null
        }
    }

    private fun readHandshakeResponse(input: java.io.InputStream, socket: Socket): String {
        val response = StringBuilder()
        var deadline = 0L
        socket.soTimeout = 8_000

        while (true) {
            try {
                val tmp = ByteArray(4096)
                val read = input.read(tmp)
                if (read < 0) break
                response.append(String(tmp, 0, read))

                if (response.contains(HANDSHAKE_END) && deadline == 0L) {
                    deadline = System.currentTimeMillis() + 120
                    socket.soTimeout = 120
                }
            } catch (_: java.net.SocketTimeoutException) {
                break
            }

            if (deadline > 0L && System.currentTimeMillis() >= deadline) break
        }

        return response.toString()
    }

    private fun openProxySocket(protectSocket: (Socket) -> Unit): Socket? {
        val candidates = linkedSetOf<InetAddress>()
        runCatching { candidates += InetAddress.getByName(PROXY_IPV6) }
        runCatching { candidates += InetAddress.getAllByName(PROXY_HOST).toList() }

        if (candidates.isEmpty()) {
            TunnelSessionStore.setState("ERROR")
            return null
        }

        for (address in candidates) {
            val socket = runCatching {
                Socket().apply {
                    protectSocket(this)
                    keepAlive = true
                    tcpNoDelay = true
                    connect(InetSocketAddress(address, PROXY_PORT), 10_000)
                }
            }.getOrNull()
            if (socket != null) return socket
        }

        TunnelSessionStore.setState("ERROR")
        return null
    }

    private data class HandshakeResult(
        val statusCode: Int,
        val headers: Map<String, String>,
        val latencyMs: Long = -1L
    )

    private fun buildHandshakeRequest(): String =
        "GET / HTTP/1.1\r\nHost: $TUNNEL_HOST\r\nUpgrade: websocket\r\n" +
            "Action: $ACTION_TUNNEL\r\nX-Client-Id: $currentClientId\r\n\r\n"

    private fun parseTunnelHandshake(response: String): HandshakeResult? {
        val blocks = extractHttpBlocks(response)
        if (blocks.isEmpty()) return null

        val candidates = blocks.mapNotNull { parseHandshakeBlock(it) }
        return candidates.firstOrNull { it.statusCode == 101 && it.headers["x-auth-state"].equals("VALID", ignoreCase = true) }
            ?: candidates.firstOrNull { it.statusCode == 101 }
            ?: candidates.lastOrNull()
    }

    private fun extractHttpBlocks(response: String): List<String> {
        val clean = response.replace("\u0000", "")
        val regex = Regex("HTTP/1\\.1\\s+\\d{3}[\\s\\S]*?(?=HTTP/1\\.1\\s+\\d{3}|$)")
        val matches = regex.findAll(clean).map { it.value.trim() }.filter { it.isNotBlank() }.toList()
        if (matches.isNotEmpty()) return matches

        return clean.split(HANDSHAKE_END)
            .map { it.trim() }
            .filter { it.startsWith("HTTP/1.1", ignoreCase = true) }
    }

    private fun parseHandshakeBlock(block: String): HandshakeResult? {
        val lines = block.split("\r\n")
        val statusCode = lines.firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: return null
        val headers = lines.drop(1).mapNotNull { line ->
            val sep = line.indexOf(':')
            if (sep <= 0) return@mapNotNull null
            val key = line.substring(0, sep).trim().lowercase()
            val value = line.substring(sep + 1).trim()
            if (key.isEmpty()) null else key to value
        }.toMap()
        return HandshakeResult(statusCode = statusCode, headers = headers)
    }
}
