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
    private const val XRAY_SOCKS5_PORT = 10808
    private const val TUNNEL_LOCAL_PORT = 10809
    private const val MUX_CONCURRENCY = 128
    private const val XUDP_CONCURRENCY = 128
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
                val cin = client.getInputStream()
                val tout = tunnel.getOutputStream()
                while (true) {
                    val n = cin.read(buffer)
                    if (n < 0) break
                    tout.write(buffer, 0, n)
                }
            }
            runCatching { tunnel.shutdownOutput() }
        }
        thread(isDaemon = true) {
            runCatching {
                val tin = tunnel.getInputStream()
                val cout = client.getOutputStream()
                while (true) {
                    val n = tin.read(buffer)
                    if (n < 0) break
                    cout.write(buffer, 0, n)
                }
            }
            upstream.interrupt()
            runCatching { client.close() }
            runCatching { tunnel.close() }
        }.join()
        upstream.join()
    }

    private fun startXray(ctx: Context) {
        runCatching {
            val binary = resolveXrayBinary(ctx) ?: return
            binary.setExecutable(true, false)
            if (!binary.canExecute()) return
            val config = File(ctx.filesDir, "xray-client.json")
                .also { it.writeText(buildClientConfig(ctx)) }
            xrayProcess = ProcessBuilder(
                listOf(binary.absolutePath, "run", "-c", config.absolutePath)
            )
                .directory(binary.parentFile ?: File(ctx.applicationInfo.nativeLibraryDir))
                .redirectErrorStream(true)
                .start()
            thread(isDaemon = true) {
                runCatching { xrayProcess?.inputStream?.copyTo(java.io.OutputStream.nullOutputStream()) }
            }
        }
    }

    private fun resolveXrayBinary(ctx: Context): File? {
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        return listOf(
            File(nativeDir, "libxray.so"),
            File(nativeDir, "xray"),
            File(ctx.filesDir, "libxray.so"),
            File(ctx.filesDir, "xray")
        ).firstOrNull { it.exists() }
    }

    private fun buildClientConfig(ctx: Context): String = """
        {
          "log": { "loglevel": "none" },
          "policy": {
            "levels": {
              "0": {
                "handshake": 4,
                "connIdle": 600,
                "uplinkOnly": 5,
                "downlinkOnly": 10,
                "bufferSize": 512
              }
            },
            "system": {
              "udpTimeout": 600,
              "connIdle": 600,
              "downlinkOnly": 10,
              "uplinkOnly": 10
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
                "concurrency": $MUX_CONCURRENCY,
                "xudpConcurrency": $XUDP_CONCURRENCY,
                "xudpProxyUDP443": "allow"
              }
            }
          ]
        }
    """.trimIndent()

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
        if (currentClientId.isBlank()) return null
        return try {
            val startMs = System.currentTimeMillis()
            val socket = openProxySocket(protectSocket) ?: return null
            socket.tcpNoDelay = true

            val out = socket.getOutputStream()
            val inp = socket.getInputStream()

            val p1 = "GET / HTTP/1.1\r\nHost: $PROXY_HOST\r\n\r\n"
            val p2 = "- / HTTP/1.1\r\nHost: $TUNNEL_HOST\r\nUpgrade: websocket\r\n" +
                "Action: tunnel\r\nX-Client-Id: $currentClientId\r\n\r\n"

            out.write(p1.toByteArray())
            out.flush()
            Thread.sleep(10)
            out.write(p2.toByteArray())
            out.flush()

            socket.soTimeout = 8000
            val raw = StringBuilder()
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline) {
                try {
                    val tmp = ByteArray(4096)
                    val n = inp.read(tmp)
                    if (n < 0) break
                    raw.append(String(tmp, 0, n))
                    if (findHttpBlock(raw.toString(), 101) != null) break
                } catch (_: java.net.SocketTimeoutException) { break }
            }

            val handshake = parseTunnelHandshake(raw.toString())

            if (handshake == null || handshake.statusCode != 101) {
                runCatching { socket.close() }
                TunnelSessionStore.setState("ERROR")
                return null
            }

            val authState = handshake.headers["x-auth-state"] ?: handshake.headers["x-status"] ?: ""
            if (authState.isNotBlank() && !authState.equals("VALID", ignoreCase = true)) {
                runCatching { socket.close() }
                TunnelSessionStore.setState("ERROR")
                return null
            }

            TunnelSessionStore.updateFromHeaders(mapOf(
                "X-Status"    to (authState.ifBlank { "VALID" }),
                "X-Name"      to (handshake.headers["x-name"] ?: "-"),
                "X-Days-Left" to (handshake.headers["x-days-left"] ?: "-")
            ))
            TunnelSessionStore.setLatency(System.currentTimeMillis() - startMs)
            TunnelSessionStore.setState("CONNECTED")

            socket.soTimeout = 0
            socket
        } catch (_: Exception) {
            TunnelSessionStore.setState("ERROR")
            null
        }
    }

    private fun openProxySocket(protectSocket: (Socket) -> Unit): Socket? {
        val candidates = linkedSetOf<InetAddress>()
        runCatching { candidates += InetAddress.getByName(PROXY_IPV6) }
        runCatching { candidates += InetAddress.getAllByName(PROXY_HOST).toList() }
        if (candidates.isEmpty()) { TunnelSessionStore.setState("ERROR"); return null }

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
        val headers: Map<String, String>
    )

    private fun findHttpBlock(raw: String, statusCode: Int): String? {
        val marker = "HTTP/1.1 $statusCode"
        val start = raw.indexOf(marker).takeIf { it >= 0 } ?: return null
        val end = raw.indexOf("\r\n\r\n", start).takeIf { it >= 0 } ?: return null
        return raw.substring(start, end)
    }

    private fun parseTunnelHandshake(raw: String): HandshakeResult? {
        val block = findHttpBlock(raw, 101) ?: return null
        return parseHandshakeBlock(block)
    }

    private fun parseHandshakeBlock(block: String): HandshakeResult? {
        val lines = block.split("\r\n")
        val statusCode = lines.firstOrNull()
            ?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: return null
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
