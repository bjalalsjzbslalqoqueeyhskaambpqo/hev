package com.blacktunnel

import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

object BtProxy {

    private const val DEFAULT_PROXY_IPV6 = "2606:4700::6812:16b7"
    private const val DEFAULT_PROXY_HOST = "emailmarketing.personal.com.ar"
    private const val DEFAULT_PROXY_PORT = 80

    private const val XRAY_SOCKS5_PORT = 10808
    private const val TUNNEL_LOCAL_PORT = 10809

    @Volatile private var xrayProcess: Process? = null
    @Volatile private var running = false
    @Volatile private var muxConcurrency: Int = 16
    @Volatile private var xudpConcurrency: Int = 32
    @Volatile private var logLevel: String = "warning"
    @Volatile private var tunnelSlots = Semaphore(16)
    @Volatile private var tunnelRetries: Int = 2
    @Volatile private var tunnelHost: String = ""
    @Volatile private var proxyHost: String = DEFAULT_PROXY_HOST
    @Volatile private var proxyPort: Int = DEFAULT_PROXY_PORT
    @Volatile private var tunnelIdentifier: String = ""

    fun start(
        ctx: Context,
        mux: Int,
        profile: String,
        identifier: String,
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ) {
        running = true
        val isPerformance = profile.equals("performance", ignoreCase = true)
        muxConcurrency = if (isPerformance) mux.coerceIn(48, 64) else mux.coerceIn(24, 42)
        xudpConcurrency = if (isPerformance) 192 else 72
        logLevel = if (isPerformance) "none" else "warning"
        tunnelSlots = Semaphore(if (isPerformance) 120 else 56)
        tunnelRetries = if (isPerformance) 6 else 3

        val endpoint = ServerEndpoint.from(BuildConfig.SERVER_URL)
        tunnelHost = endpoint.host
        proxyHost = endpoint.host
        proxyPort = endpoint.port
        tunnelIdentifier = identifier.trim()

        logger("BtProxy.start() mode=${BuildConfig.APP_MODE} server=${endpoint.baseUrl} id=$tunnelIdentifier")
        TunnelSessionStore.setState("CONNECTING")

        thread(isDaemon = true, name = "btproxy-init") {
            startTunnelBridge(protectSocket, logger)
            startXray(ctx, logger)
        }
    }

    fun stop() {
        running = false
        xrayProcess?.destroy()
        xrayProcess = null
        TunnelSessionStore.reset()
    }

    private fun startTunnelBridge(
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ) {
        val srv = ServerSocket(TUNNEL_LOCAL_PORT, 128, InetAddress.getByName("127.0.0.1"))
        logger("Bridge escuchando en 127.0.0.1:$TUNNEL_LOCAL_PORT")

        thread(isDaemon = true, name = "bridge-accept") {
            try {
                while (running) {
                    val client = srv.accept()
                    client.tcpNoDelay = true
                    thread(isDaemon = true, name = "bridge-conn") {
                        tunnelSlots.acquire()
                        val tunnel = openTunnelWithRetry(protectSocket, logger)
                        if (tunnel == null) {
                            logger("ERROR no se pudo abrir túnel para conexión local")
                            runCatching { client.close() }
                            tunnelSlots.release()
                            return@thread
                        }
                        logger("Túnel TCP abierto para bridge")
                        relay(client, tunnel)
                        tunnelSlots.release()
                    }
                }
            } catch (_: Exception) {
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
            runCatching { client.close() }
            runCatching { tunnel.close() }
        }
        up.join()
        down.join()
    }

    private fun startXray(ctx: Context, logger: (String) -> Unit) {
        try {
            val nativeLibDir = ctx.applicationInfo.nativeLibraryDir
            val candidates = listOf(
                java.io.File(nativeLibDir, "libxray.so"),
                java.io.File(nativeLibDir, "xray"),
                java.io.File(ctx.filesDir, "libxray.so"),
                java.io.File(ctx.filesDir, "xray")
            )
            val binary = candidates.firstOrNull { it.exists() } ?: run {
                logger("ERROR xray no encontrado en rutas conocidas")
                return
            }
            runCatching { binary.setExecutable(true, false) }
            if (!binary.canExecute()) {
                logger("ERROR xray no ejecutable: ${binary.absolutePath}")
                return
            }

            val config = java.io.File(ctx.filesDir, "xray-client.json")
            config.writeText(buildClientConfig(ctx))

            val cmd = listOf(binary.absolutePath, "run", "-c", config.absolutePath)
            val process = ProcessBuilder(cmd)
                .directory(binary.parentFile ?: java.io.File(nativeLibDir))
                .redirectErrorStream(true)
                .start()
            xrayProcess = process

            thread(isDaemon = true, name = "xray-log") {
                process.inputStream.bufferedReader().forEachLine { line ->
                    logger("[xray] $line")
                }
            }
            logger("xray proceso iniciado")
        } catch (e: Exception) {
            logger("ERROR iniciando xray: ${e.message}")
        }
    }

    private fun buildClientConfig(ctx: Context): String {
        return """
            {
              "log": { "loglevel": "$logLevel" },
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
                            "id": "${tunnelIdentifier.ifBlank { "00000000-0000-0000-0000-000000000000" }}",
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
                    "concurrency": $muxConcurrency,
                    "xudpConcurrency": $xudpConcurrency,
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
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ): Socket? {
        return try {
            val startMs = System.currentTimeMillis()
            val sock = openProxySocket(protectSocket, logger) ?: return null
            sock.tcpNoDelay = true
            val out = sock.getOutputStream()
            val inp = sock.getInputStream()

            val p1 = "GET / HTTP/1.1\r\nHost: $proxyHost\r\n\r\n"
            out.write(p1.toByteArray())
            out.flush()
            Thread.sleep(200)

            val p2 = "- / HTTP/1.1\r\nHost: $tunnelHost\r\nUpgrade: websocket\r\nAction: tunnel\r\nX-Identifier: $tunnelIdentifier\r\n\r\n"
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
            if (headersForUi["x-status"].isNullOrBlank()) {
                headersForUi["x-status"] = if (handshake?.statusCode == 101) "OK" else "OPEN"
            }
            TunnelSessionStore.updateFromHeaders(
                mapOf(
                    "X-Status" to (headersForUi["x-status"] ?: "-"),
                    "X-Name" to (headersForUi["x-name"] ?: "-"),
                    "X-Expire" to (headersForUi["x-expire"] ?: "-"),
                    "X-Days-Left" to (headersForUi["x-days-left"] ?: "-"),
                    "X-Premium" to (headersForUi["x-premium"] ?: "-")
                )
            )

            sock.soTimeout = 0
            TunnelSessionStore.setLatency(System.currentTimeMillis() - startMs)
            TunnelSessionStore.setState("CONNECTED")
            sock
        } catch (e: Exception) {
            logger("ERROR abriendo túnel: ${e.message}")
            TunnelSessionStore.setState("ERROR")
            null
        }
    }

    private fun openTunnelWithRetry(
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ): Socket? {
        repeat(tunnelRetries) { attempt ->
            val tunnel = openTunnel(protectSocket, logger)
            if (tunnel != null) return tunnel
            if (attempt < tunnelRetries - 1) {
                val backoff = (250L * (attempt + 1)).coerceAtMost(1200L)
                Thread.sleep(backoff)
            }
        }
        return null
    }

    private fun openProxySocket(
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ): Socket? {
        val candidates = linkedSetOf<InetAddress>()
        runCatching { candidates += InetAddress.getByName(DEFAULT_PROXY_IPV6) }
        runCatching { candidates += InetAddress.getAllByName(proxyHost).toList() }
            .onFailure { logger("WARN resolución DNS de proxy falló: ${it.message}") }

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
                socket.connect(InetSocketAddress(address, proxyPort), 10_000)
                return socket
            }
        }

        TunnelSessionStore.setState("ERROR")
        return null
    }

    private data class HandshakeResult(
        val statusCode: Int,
        val headers: Map<String, String>
    )

    private fun parseTunnelHandshake(response: String): HandshakeResult? {
        val candidates = response
            .split("\r\n\r\n")
            .map { it.trim() }
            .filter { it.startsWith("HTTP/1.1", ignoreCase = true) }
            .map { parseHandshakeBlock(it) }

        if (candidates.isEmpty()) return null

        return candidates.firstOrNull {
            it.statusCode == 101 && it.headers["x-status"].isNullOrBlank().not()
        } ?: candidates.firstOrNull {
            it.statusCode == 101 && it.headers["upgrade"].equals("websocket", ignoreCase = true)
        } ?: candidates.lastOrNull {
            it.statusCode == 101
        }
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

    private data class ServerEndpoint(
        val host: String,
        val port: Int,
        val baseUrl: String
    ) {
        companion object {
            fun from(raw: String): ServerEndpoint {
                val normalized = raw.trim().ifBlank { "https://example.com" }
                val url = runCatching { URL(normalized) }.getOrElse { URL("https://example.com") }
                val port = if (url.port > 0) url.port else if (url.protocol.equals("https", true)) 443 else 80
                val base = "${url.protocol}://${url.host}${if (url.port > 0) ":${url.port}" else ""}"
                return ServerEndpoint(url.host, port, base)
            }
        }
    }
}
