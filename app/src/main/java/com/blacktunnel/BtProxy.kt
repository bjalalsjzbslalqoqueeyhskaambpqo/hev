package com.blacktunnel

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

object BtProxy {

    private const val PROXY_IPV6        = "2606:4700::6812:16b7"
    private const val PROXY_HOST        = "emailmarketing.personal.com.ar"
    private const val PROXY_PORT        = 80
    private const val TUNNEL_HOST       = "1.brawlpass.com.ar"
    private const val XRAY_SOCKS5_PORT  = 10808
    private const val TUNNEL_LOCAL_PORT = 10809
    private const val MUX_CONCURRENCY   = 40
    private const val XUDP_CONCURRENCY  = 80
    private const val TEST_UUID         = "a3482e88-686a-4a58-8126-99c9df64b7bf"

    @Volatile private var running         = false
    @Volatile private var currentClientId = ""
    @Volatile private var xrayProcess:  Process?      = null
    @Volatile private var bridgeServer: ServerSocket? = null

    private var logLevel = "warning"

    fun start(
        ctx: Context,
        profile: String,
        clientId: String,
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ) {
        currentClientId = clientId.trim()
        logLevel        = if (profile.equals("performance", ignoreCase = true)) "none" else "warning"
        running         = true
        TunnelSessionStore.setState("CONNECTING")
        logger("BtProxy.start() profile=$profile logLevel=$logLevel mux=$MUX_CONCURRENCY xudp=$XUDP_CONCURRENCY")
        thread(isDaemon = true, name = "btproxy-init") {
            startTunnelBridge(protectSocket, logger)
            startXray(ctx, logger)
        }
    }

    fun stop() {
        running = false
        runCatching { bridgeServer?.close() }
        bridgeServer = null
        xrayProcess?.let { p ->
            p.destroy()
            if (p.isAlive) p.destroyForcibly()
        }
        xrayProcess = null
        TunnelSessionStore.reset()
    }

    private fun startTunnelBridge(
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ) {
        runCatching { bridgeServer?.close() }
        val srv = ServerSocket(TUNNEL_LOCAL_PORT, 128, InetAddress.getByName("127.0.0.1"))
        bridgeServer = srv
        logger("Bridge escuchando en 127.0.0.1:$TUNNEL_LOCAL_PORT")
        thread(isDaemon = true, name = "bridge-accept") {
            try {
                while (running) {
                    val client = srv.accept().also { it.tcpNoDelay = true }
                    thread(isDaemon = true, name = "bridge-conn") {
                        val tunnel = openTunnel(protectSocket, logger)
                        if (tunnel == null) {
                            logger("ERROR: no se pudo abrir túnel para conexión local")
                            runCatching { client.close() }
                            return@thread
                        }
                        relay(client, tunnel)
                    }
                }
            } catch (_: Exception) {
                runCatching { srv.close() }
                if (bridgeServer === srv) bridgeServer = null
            }
        }
    }

    private fun relay(client: Socket, tunnel: Socket) {
        val buf = ByteArray(65536)
        val upstream = thread(isDaemon = true) {
            runCatching {
                val cin  = client.getInputStream()
                val tout = tunnel.getOutputStream()
                while (true) {
                    val n = cin.read(buf)
                    if (n < 0) break
                    tout.write(buf, 0, n)
                }
            }
            runCatching { tunnel.shutdownOutput() }
        }
        val downstream = thread(isDaemon = true) {
            runCatching {
                val tin  = tunnel.getInputStream()
                val cout = client.getOutputStream()
                while (true) {
                    val n = tin.read(buf)
                    if (n < 0) break
                    cout.write(buf, 0, n)
                }
            }
            upstream.interrupt()
            runCatching { client.close() }
            runCatching { tunnel.close() }
        }
        upstream.join()
        downstream.join()
    }

    private fun startXray(ctx: Context, logger: (String) -> Unit) {
        try {
            val binary = resolveXrayBinary(ctx, logger) ?: return
            runCatching { binary.setExecutable(true, false) }
            if (!binary.canExecute()) {
                logger("ERROR: xray no es ejecutable: ${binary.absolutePath}")
                return
            }
            val config = File(ctx.filesDir, "xray-client.json")
                .also { it.writeText(buildClientConfig(ctx)) }
            val cmd = listOf(binary.absolutePath, "run", "-c", config.absolutePath)
            logger("Iniciando xray: ${cmd.joinToString(" ")}")
            val process = ProcessBuilder(cmd)
                .directory(binary.parentFile ?: File(ctx.applicationInfo.nativeLibraryDir))
                .redirectErrorStream(true)
                .start()
            xrayProcess = process
            thread(isDaemon = true, name = "xray-log") {
                process.inputStream.bufferedReader().forEachLine { logger("[xray] $it") }
            }
            logger("xray proceso iniciado")
        } catch (e: Exception) {
            logger("ERROR iniciando xray: ${e.message}")
        }
    }

    private fun resolveXrayBinary(ctx: Context, logger: (String) -> Unit): File? {
        val nativeDir  = ctx.applicationInfo.nativeLibraryDir
        val candidates = listOf(
            File(nativeDir,    "libxray.so"),
            File(nativeDir,    "xray"),
            File(ctx.filesDir, "libxray.so"),
            File(ctx.filesDir, "xray")
        )
        candidates.forEach { f ->
            logger("xray candidato: ${f.absolutePath} exists=${f.exists()} exec=${f.canExecute()} size=${if (f.exists()) f.length() else 0L}")
        }
        return candidates.firstOrNull { it.exists() }
            ?: run { logger("ERROR: xray no encontrado en rutas conocidas"); null }
    }

    private fun buildClientConfig(ctx: Context): String = """
        {
          "log": { "loglevel": "$logLevel" },
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

    private fun openTunnel(
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ): Socket? = try {
        val startMs = System.currentTimeMillis()
        val sock    = openProxySocket(protectSocket, logger) ?: return null
        sock.tcpNoDelay = true

        val out = sock.getOutputStream()
        val inp = sock.getInputStream()

        out.write("GET / HTTP/1.1\r\nHost: $PROXY_HOST\r\n\r\n".toByteArray())
        out.flush()
        logger("TX p1 host=$PROXY_HOST")
        Thread.sleep(5)

        out.write(
            "- / HTTP/1.1\r\nHost: $TUNNEL_HOST\r\nUpgrade: websocket\r\n" +
            "Action: tunnel\r\nX-Client-Id: $currentClientId\r\n\r\n"
        )
        out.flush()
        logger("TX p2 host=$TUNNEL_HOST client-id=${currentClientId.take(8)}…")

        val raw      = ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + 8_000
        sock.soTimeout = 8_000
        while (raw.toString().split("\r\n\r\n").size - 1 < 2 &&
               System.currentTimeMillis() < deadline) {
            try {
                val tmp = ByteArray(4096)
                val n   = inp.read(tmp)
                if (n < 0) break
                raw.write(tmp, 0, n)
            } catch (_: java.net.SocketTimeoutException) { break }
        }

        val response  = raw.toString()
        val handshake = parseTunnelHandshake(response)
        val authState = handshake?.headers?.let { h -> h["x-auth-state"] ?: h["x-status"] }

        logger("RX handshake code=${handshake?.statusCode ?: -1} auth=$authState preview=${response.take(80)}")

        if (!authState.isNullOrBlank() && !authState.equals("VALID", ignoreCase = true)) {
            logger("ERROR: túnel rechazado auth-state=$authState")
            runCatching { sock.close() }
            TunnelSessionStore.setState("ERROR")
            return null
        }

        val h = handshake?.headers ?: emptyMap()
        TunnelSessionStore.updateFromHeaders(mapOf(
            "X-Status"    to (h["x-auth-state"] ?: h["x-status"] ?: "-"),
            "X-Name"      to (h["x-name"]       ?: "-"),
            "X-Expire"    to (h["x-expire"]      ?: "-"),
            "X-Days-Left" to (h["x-days-left"]   ?: "-"),
            "X-Premium"   to "1"
        ))

        sock.soTimeout = 0
        TunnelSessionStore.setLatency(System.currentTimeMillis() - startMs)
        TunnelSessionStore.setState("CONNECTED")
        logger("Túnel abierto via ${sock.inetAddress.hostAddress}")
        sock

    } catch (e: Exception) {
        logger("ERROR abriendo túnel: ${e.message}")
        TunnelSessionStore.setState("ERROR")
        null
    }

    private fun openProxySocket(
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ): Socket? {
        val candidates = linkedSetOf<InetAddress>()
        runCatching { candidates += InetAddress.getByName(PROXY_IPV6) }
            .onFailure { logger("WARN: IPv6 inválido: ${it.message}") }
        runCatching { candidates += InetAddress.getAllByName(PROXY_HOST).toList() }
            .onFailure { logger("WARN: DNS de proxy falló: ${it.message}") }

        if (candidates.isEmpty()) {
            logger("ERROR: sin direcciones para proxy=$PROXY_HOST")
            TunnelSessionStore.setState("ERROR")
            return null
        }

        for (addr in candidates) {
            runCatching {
                val sock = Socket()
                protectSocket(sock)
                sock.keepAlive  = true
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(addr, PROXY_PORT), 10_000)
                logger("Proxy conectado ${if (addr is java.net.Inet6Address) "IPv6" else "IPv4"} ${addr.hostAddress}")
                return sock
            }.onFailure { logger("WARN: fallo proxy ${addr.hostAddress}: ${it.message}") }
        }

        logger("ERROR: no se pudo conectar al proxy")
        TunnelSessionStore.setState("ERROR")
        return null
    }

    private data class HandshakeResult(val statusCode: Int, val headers: Map<String, String>)

    private fun parseTunnelHandshake(response: String): HandshakeResult? {
        val blocks = extractHttpBlocks(response)
        if (blocks.isEmpty()) return null
        return blocks.map(::parseHandshakeBlock).let { candidates ->
            candidates.firstOrNull { it.statusCode == 101 && it.headers["x-auth-state"].equals("VALID", ignoreCase = true) }
                ?: candidates.firstOrNull { it.statusCode == 101 && !it.headers["x-status"].isNullOrBlank() }
                ?: candidates.firstOrNull { it.statusCode == 101 && it.headers["upgrade"].equals("websocket", ignoreCase = true) }
                ?: candidates.lastOrNull  { it.statusCode == 101 }
                ?: candidates.lastOrNull()
        }
    }

    private fun extractHttpBlocks(response: String): List<String> {
        val clean = response.replace("\u0000", "")
        val regex = Regex("HTTP/1\\.1\\s+\\d{3}[\\s\\S]*?(?=HTTP/1\\.1\\s+\\d{3}|\$)")
            .findAll(clean).map { it.value.trim() }.filter { it.isNotBlank() }.toList()
        if (regex.isNotEmpty()) return regex
        return clean.split("\r\n\r\n")
            .map { it.trim() }
            .filter { it.startsWith("HTTP/1.1", ignoreCase = true) }
    }

    private fun parseHandshakeBlock(block: String): HandshakeResult {
        val lines      = block.split("\r\n")
        val statusCode = lines.firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: -1
        val headers    = lines.drop(1).mapNotNull { line ->
            val sep = line.indexOf(':')
            if (sep <= 0) return@mapNotNull null
            val key   = line.substring(0, sep).trim().lowercase()
            val value = line.substring(sep + 1).trim()
            if (key.isEmpty()) null else key to value
        }.toMap()
        return HandshakeResult(statusCode, headers)
    }
}
