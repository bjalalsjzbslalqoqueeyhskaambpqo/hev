package com.blacktunnel

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

object BtProxy {

    private const val PROXY_IPV6  = "2606:4700::6812:16b7"
    private const val PROXY_HOST  = "emailmarketing.personal.com.ar"
    private const val PROXY_PORT  = 80
    private const val TUNNEL_HOST = "7.brawlpass.com.ar"

    private const val XRAY_SOCKS5_PORT = 10808
    private const val TUNNEL_LOCAL_PORT = 10809
    private const val TEST_UUID = "a3482e88-686a-4a58-8126-99c9df64b7bf"
    private const val HANDSHAKE_GAP_MS = 5L

    @Volatile private var xrayProcess: Process? = null
    @Volatile private var bridgeServer: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var currentClientId: String = ""
    private val activeSockets = Collections.synchronizedSet(mutableSetOf<Socket>())

    fun start(
        ctx: Context,
        clientId: String,
        protectSocket: (Socket) -> Unit
    ) {
        running = true
        currentClientId = clientId.trim()
        TunnelSessionStore.setState("CONNECTING")

        thread(isDaemon = true, name = "btproxy-init") {
            startTunnelBridge(protectSocket)
            startXray(ctx)
        }
    }

    fun prepareTunnel(
        clientId: String,
        protectSocket: (Socket) -> Unit
    ): Boolean {
        currentClientId = clientId.trim()
        val authSocket = openProxySocket(protectSocket) ?: return false
        return runCatching {
            authSocket.tcpNoDelay = true
            val out = authSocket.getOutputStream()
            val inp = authSocket.getInputStream()

            val p1 = "GET / HTTP/1.1\r\nHost: $PROXY_HOST\r\n\r\n"
            out.write(p1.toByteArray())
            out.flush()
            Thread.sleep(HANDSHAKE_GAP_MS)

            val p2 = "- / HTTP/1.1\r\nHost: $TUNNEL_HOST\r\nUpgrade: websocket\r\nAction: auth\r\nX-Client-Id: $currentClientId\r\n\r\n"
            out.write(p2.toByteArray())
            out.flush()

            authSocket.soTimeout = 8000
            val raw = ByteArrayOutputStream()
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline) {
                try {
                    val tmp = ByteArray(4096)
                    val n = inp.read(tmp)
                    if (n <= 0) break
                    raw.write(tmp, 0, n)
                    val text = raw.toString()
                    val blocks = text.split("\r\n\r\n").size - 1
                    if (blocks >= 2) break
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }
            }

            val handshake = parseTunnelHandshake(raw.toString()) ?: return@runCatching false
            val headers = handshake.headers
            val authState = headers["x-auth-state"] ?: headers["x-status"] ?: ""
            val status = if (authState.isBlank()) {
                if (handshake.statusCode == 101) "VALID" else "INVALID"
            } else {
                authState
            }
            TunnelSessionStore.updateFromHeaders(
                mapOf(
                    "X-Status" to status,
                    "X-Name" to (headers["x-name"] ?: "-"),
                    "X-Expire" to (headers["x-expire"] ?: "-"),
                    "X-Days-Left" to (headers["x-days-left"] ?: "-"),
                    "X-Premium" to "1"
                )
            )
            handshake.statusCode == 101 && status.equals("VALID", ignoreCase = true)
        }.getOrDefault(false).also {
            releaseSocket(authSocket)
            runCatching { authSocket.close() }
        }
    }

    fun stop() {
        running = false
        runCatching { bridgeServer?.close() }
        bridgeServer = null
        synchronized(activeSockets) {
            activeSockets.forEach { socket -> runCatching { socket.close() } }
            activeSockets.clear()
        }
        xrayProcess?.destroy()
        xrayProcess = null
        TunnelSessionStore.reset()
    }

    private fun startTunnelBridge(
        protectSocket: (Socket) -> Unit
    ) {
        val srv = ServerSocket(TUNNEL_LOCAL_PORT, 128, InetAddress.getByName("127.0.0.1"))
        bridgeServer = srv

        thread(isDaemon = true, name = "bridge-accept") {
            try {
                while (running) {
                    val client = srv.accept()
                    trackSocket(client)
                    client.tcpNoDelay = true
                    thread(isDaemon = true, name = "bridge-conn") {
                        val tunnel = openTunnel(protectSocket)
                        if (tunnel == null) {
                            releaseSocket(client)
                            runCatching { client.close() }
                            return@thread
                        }
                        relay(client, tunnel)
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { srv.close() }
            }
        }
    }

    private fun relay(client: Socket, tunnel: Socket) {
        val buf = ByteArray(65536)
        val up = thread(isDaemon = true) {
            runCatching {
                val cin = client.getInputStream()
                val tout = tunnel.getOutputStream()
                while (true) {
                    val n = cin.read(buf)
                    if (n < 0) break
                    tout.write(buf, 0, n)
                }
            }
            runCatching { tunnel.shutdownOutput() }
        }
        val down = thread(isDaemon = true) {
            runCatching {
                val tin = tunnel.getInputStream()
                val cout = client.getOutputStream()
                while (true) {
                    val n = tin.read(buf)
                    if (n < 0) break
                    cout.write(buf, 0, n)
                }
            }
            up.interrupt()
            releaseSocket(client)
            releaseSocket(tunnel)
            runCatching { client.close() }
            runCatching { tunnel.close() }
        }
        up.join()
        down.join()
    }

    private fun startXray(ctx: Context) {
        try {
            val nativeLibDir = ctx.applicationInfo.nativeLibraryDir
            val candidates = listOf(
                File(nativeLibDir, "libxray.so"),
                File(nativeLibDir, "xray"),
                File(ctx.filesDir, "libxray.so"),
                File(ctx.filesDir, "xray")
            )
            val binary = candidates.firstOrNull { it.exists() } ?: return
            runCatching { binary.setExecutable(true, false) }
            if (!binary.canExecute()) return

            val config = File(ctx.filesDir, "xray-client.json")
            config.writeText(buildClientConfig(ctx))

            val cmd = listOf(
                binary.absolutePath,
                "run",
                "-c",
                config.absolutePath
            )
            val process = ProcessBuilder(cmd)
                .directory(binary.parentFile ?: File(nativeLibDir))
                .redirectErrorStream(true)
                .start()
            xrayProcess = process

            thread(isDaemon = true, name = "xray-log") {
                process.inputStream.bufferedReader().forEachLine { }
            }
        } catch (e: Exception) {
        }
    }

    private fun buildClientConfig(ctx: Context): String {
        return """
            {
              "log": { "loglevel": "none" },
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
                        "users": [
                          {
                            "id": "$TEST_UUID",
                            "encryption": "none"
                          }
                        ]
                      }
                    ]
                  },
                  "streamSettings": {
                    "network": "tcp",
                    "security": "none"
                  },
                  "mux": {
                    "enabled": true,
                    "concurrency": 256,
                    "xudpConcurrency": 512,
                    "xudpProxyUDP443": "allow"
                  }
                }
              ]
            }
        """.trimIndent()
    }


    private fun buildHotspotInbound(ctx: Context): String {
        if (!TunnelPrefs.isHotspotProxyEnabled(ctx)) return ""
        val hotspotIp = getHotspotIp() ?: return ""
        return "," +
            """
                {
                  "protocol": "socks",
                  "listen": "0.0.0.0",
                  "port": 1080,
                  "settings": {
                    "udp": true,
                    "ip": "$hotspotIp"
                  }
                }
            """.trimIndent()
    }

    private fun getHotspotIp(): String? {
        return runCatching {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            val candidates = mutableListOf<Pair<String, String>>()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val name = intf.name.lowercase()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        candidates += name to addr.hostAddress
                    }
                }
            }
            candidates.firstOrNull { (name, ip) ->
                (name.contains("ap") || name.contains("swlan") || name.contains("rndis") || name.contains("wlan")) &&
                    !ip.startsWith("127.")
            }?.second ?: candidates.firstOrNull()?.second
        }.getOrNull()
    }

    private fun openTunnel(
        protectSocket: (Socket) -> Unit
    ): Socket? {
        return try {
            val startMs = System.currentTimeMillis()
            val sock = openProxySocket(protectSocket) ?: return null
            sock.tcpNoDelay = true
            val out = sock.getOutputStream()
            val inp = sock.getInputStream()

            val p1 = "GET / HTTP/1.1\r\nHost: $PROXY_HOST\r\n\r\n"
            out.write(p1.toByteArray())
            out.flush()
            Thread.sleep(HANDSHAKE_GAP_MS)

            val p2 = "- / HTTP/1.1\r\nHost: $TUNNEL_HOST\r\nUpgrade: websocket\r\nAction: tunnel\r\nX-Client-Id: $currentClientId\r\n\r\n"
            out.write(p2.toByteArray())
            out.flush()

            sock.soTimeout = 8000
            val buf = ByteArrayOutputStream()
            var blocks = 0
            val deadline = System.currentTimeMillis() + 8000
            while (blocks < 2 && System.currentTimeMillis() < deadline) {
                try {
                    val tmp = ByteArray(4096)
                    val n = inp.read(tmp)
                    if (n < 0) break
                    buf.write(tmp, 0, n)
                    blocks = buf.toString().split("\r\n\r\n").size - 1
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }
            }

            val resp = buf.toString()
            val handshake = parseTunnelHandshake(resp)
            val headersForUi = handshake?.headers?.toMutableMap() ?: mutableMapOf()
            val authState = headersForUi["x-auth-state"] ?: headersForUi["x-status"]
            if (headersForUi["x-status"].isNullOrBlank()) {
                headersForUi["x-status"] = authState ?: if (handshake?.statusCode == 101) "VALID" else "INVALID"
            }
            if (!authState.isNullOrBlank() && !authState.equals("VALID", ignoreCase = true)) {
                releaseSocket(sock)
                runCatching { sock.close() }
                TunnelSessionStore.setState("ERROR")
                return null
            }
            TunnelSessionStore.updateFromHeaders(
                mapOf(
                    "X-Status" to (headersForUi["x-status"] ?: "-"),
                    "X-Name" to (headersForUi["x-name"] ?: "-"),
                    "X-Expire" to (headersForUi["x-expire"] ?: "-"),
                    "X-Days-Left" to (headersForUi["x-days-left"] ?: "-"),
                    "X-Premium" to "1"
                )
            )

            sock.soTimeout = 0
            TunnelSessionStore.setLatency(System.currentTimeMillis() - startMs)
            TunnelSessionStore.setState("CONNECTED")
            sock
        } catch (e: Exception) {
            TunnelSessionStore.setState("ERROR")
            null
        }
    }

    private fun openProxySocket(
        protectSocket: (Socket) -> Unit
    ): Socket? {
        val candidates = linkedSetOf<InetAddress>()
        runCatching { candidates += InetAddress.getByName(PROXY_IPV6) }
        runCatching { candidates += InetAddress.getAllByName(PROXY_HOST).toList() }

        if (candidates.isEmpty()) {
            TunnelSessionStore.setState("ERROR")
            return null
        }

        candidates.forEach { address ->
            runCatching {
                val socket = Socket()
                protectSocket(socket)
                socket.keepAlive = true
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(address, PROXY_PORT), 10_000)
                trackSocket(socket)
                return socket
            }.onFailure { }
        }

        TunnelSessionStore.setState("ERROR")
        return null
    }

    private fun trackSocket(socket: Socket) {
        activeSockets.add(socket)
    }

    private fun releaseSocket(socket: Socket) {
        activeSockets.remove(socket)
    }

    private data class HandshakeResult(
        val statusCode: Int,
        val headers: Map<String, String>
    )

    private fun parseTunnelHandshake(response: String): HandshakeResult? {
        val blocks = response.split("\r\n\r\n").filter { it.trimStart().startsWith("HTTP/1.1") }
        val block = blocks.firstOrNull { it.contains("101") && it.contains("X-Auth-State", ignoreCase = true) }
            ?: blocks.firstOrNull { it.contains("101") }
            ?: return null
        return parseHandshakeBlock(block)
    }

    private fun parseHandshakeBlock(block: String): HandshakeResult {
        val lines = block.split("\r\n")
        val statusCode = lines
            .firstOrNull()
            ?.split(" ")
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: -1

        val headers = lines
            .drop(1)
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) return@mapNotNull null
                val key = line.substring(0, separator).trim().lowercase()
                val value = line.substring(separator + 1).trim()
                if (key.isEmpty()) null else key to value
            }
            .toMap()

        return HandshakeResult(statusCode, headers)
    }

}
