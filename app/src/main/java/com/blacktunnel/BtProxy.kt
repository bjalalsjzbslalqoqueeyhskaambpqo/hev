package com.blacktunnel

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

object BtProxy {

    private val DECOY_IPV6_CANDIDATES = listOf("2606:4700::6812:16b7", "2606:4700::6812:17b7")
    private const val DECOY_IPV4  = "104.18.23.183"
    private const val DECOY_HOST  = "emailmarketing.personal.com.ar"
    private const val PROXY_PORT  = 80

    private const val XRAY_SOCKS5_PORT = 10808
    private const val TUNNEL_LOCAL_PORT = 10809
    private const val TEST_UUID = "a3482e88-686a-4a58-8126-99c9df64b7bf"

    @Volatile private var xrayProcess: Process? = null
    @Volatile private var running = false
    private val logLevel: String = "none"
    private val tunnelSlots = Semaphore(120)
    @Volatile private var bridgeServerSocket: ServerSocket? = null
    @Volatile private var clientId: String = ""
    @Volatile private var selectedServerHost: String = ""
    private val terminalStatuses = setOf("INVALID", "EXPIRED", "DENIED", "CENTRAL_OFFLINE", "BANNED")
    data class AuthPreflightResult(
        val ok: Boolean,
        val status: String,
        val statusCode: Int,
        val userMessage: String
    )

    fun start(
        ctx: Context,
        serverHost: String,
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ) {
        running = true
        clientId = TunnelPrefs.getOrCreateClientId(ctx)
        selectedServerHost = serverHost.trim()
        logger("BtProxy.start()")
        TunnelSessionStore.setState("CONNECTING")

        thread(isDaemon = true, name = "btproxy-init") {
            startTunnelBridge(protectSocket, logger)
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline) {
                if (TunnelSessionStore.current().state == "CONNECTED") break
                Thread.sleep(100)
            }
            startXray(ctx, logger)
        }
    }

    fun preflightAuth(
        ctx: Context,
        serverHost: String,
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ): AuthPreflightResult {
        selectedServerHost = serverHost.trim()
        clientId = TunnelPrefs.getOrCreateClientId(ctx)
        if (selectedServerHost.isBlank()) {
            TunnelSessionStore.updateFromHeaders(mapOf("X-Status" to "INVALID"))
            logger("Preflight AUTH inválido: host vacío")
            return AuthPreflightResult(false, "INVALID", -1, "Servidor inválido. Elegí un servidor e intentá de nuevo.")
        }

        val response = runHandshakeRequest(action = "auth", protectSocket = protectSocket, logger = logger)
            ?: return AuthPreflightResult(false, "UNKNOWN", -1, "No se pudo verificar la autenticación con el servidor.")
        var handshake = pickHandshakeWithHeaders(response.data)
        var headers = handshake.headers
        var status = (headers["x-status"] ?: "UNKNOWN").uppercase()
        runCatching { response.socket.close() }

        if (status == "-" || status == "UNKNOWN" || status == "OPEN") {
            logger("Preflight AUTH vacío/incompleto, reintentando una vez")
            Thread.sleep(180)
            val retry = runHandshakeRequest(action = "auth", protectSocket = protectSocket, logger = logger)
            if (retry != null) {
                handshake = pickHandshakeWithHeaders(retry.data)
                headers = handshake.headers
                status = (headers["x-status"] ?: "UNKNOWN").uppercase()
                runCatching { retry.socket.close() }
            }
        }
        TunnelSessionStore.updateFromHeaders(
            mapOf(
                "X-Status" to status,
                "X-Name" to (headers["x-name"] ?: "-"),
                "X-Expire" to (headers["x-expire"] ?: "-"),
                "X-Days-Left" to (headers["x-days-left"] ?: "-"),
                "X-Premium" to (headers["x-premium"] ?: "-")
            )
        )
        logger("Preflight AUTH code=${handshake.statusCode} status=$status")
        val userMessage = when (status) {
            "OK" -> "Autenticación válida."
            "INVALID" -> "ID no registrado. Registralo y esperá unos segundos para reintentar."
            "UNKNOWN", "-", "OPEN" -> "ID aún no descolgado o respuesta incompleta. Reintentá en ~8 segundos."
            else -> "Autenticación rechazada por el servidor (estado=$status)."
        }
        return AuthPreflightResult(status == "OK", status, handshake.statusCode, userMessage)
    }

    fun stop() {
        running = false
        runCatching { bridgeServerSocket?.close() }
        bridgeServerSocket = null
        val proc = xrayProcess
        runCatching { proc?.destroy() }
        runCatching {
            if (proc != null && proc.isAlive) {
                proc.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
        runCatching {
            if (proc != null && proc.isAlive) {
                proc.destroyForcibly()
            }
        }
        xrayProcess = null
        TunnelSessionStore.reset()
    }

    private fun startTunnelBridge(
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ) {
        runCatching { bridgeServerSocket?.close() }
        val srv = ServerSocket(TUNNEL_LOCAL_PORT, 128, InetAddress.getByName("127.0.0.1"))
        bridgeServerSocket = srv
        logger("Bridge escuchando en 127.0.0.1:$TUNNEL_LOCAL_PORT")

        thread(isDaemon = true, name = "bridge-accept") {
            try {
                while (running) {
                    val client = srv.accept()
                    client.tcpNoDelay = true
                    thread(isDaemon = true, name = "bridge-conn") {
                        tunnelSlots.acquire()
                        val tunnel = openTunnel(protectSocket, logger)
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
            } finally {
                runCatching { srv.close() }
                if (bridgeServerSocket === srv) bridgeServerSocket = null
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
                File(nativeLibDir, "libxray.so"),
                File(nativeLibDir, "xray"),
                File(ctx.filesDir, "libxray.so"),
                File(ctx.filesDir, "xray")
            )
            candidates.forEach { c ->
                logger("xray candidato: ${c.absolutePath} exists=${c.exists()} exec=${c.canExecute()} size=${if (c.exists()) c.length() else 0L}")
            }
            val binary = candidates.firstOrNull { it.exists() } ?: run {
                logger("ERROR xray no encontrado en rutas conocidas")
                return
            }
            runCatching { binary.setExecutable(true, false) }
            if (!binary.canExecute()) {
                logger("ERROR xray no ejecutable: ${binary.absolutePath}")
                return
            }

            val config = File(ctx.filesDir, "xray-client.json")
            config.writeText(buildClientConfig(ctx))

            val cmd = listOf(
                binary.absolutePath,
                "run",
                "-c",
                config.absolutePath
            )
            logger("Iniciando xray: ${cmd.joinToString(" ")}")

            val process = ProcessBuilder(cmd)
                .directory(binary.parentFile ?: File(nativeLibDir))
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
              "policy": {
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
                    "concurrency": 1024,
                    "xudpConcurrency": 1024,
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
            val response = runHandshakeRequest(
                action = "tunnel",
                protectSocket = protectSocket,
                logger = logger
            )
                ?: return null

            val sock = response.socket
            val handshake = pickHandshakeWithHeaders(response.data)
            val status = resolveStatusExhaustive(response.data, handshake.headers["x-status"])

            if (handshake.statusCode != 101) {
                logger("Túnel rechazado code=${handshake.statusCode}")
                runCatching { sock.close() }
                return null
            }

            if (status in terminalStatuses) {
                logger("Túnel rechazado status=$status")
                runCatching { sock.close() }
                return null
            }

            if (status != "OK") {
                logger("Túnel rechazado status no válido=$status")
                runCatching { sock.close() }
                return null
            }

            TunnelSessionStore.updateFromHeaders(
                mapOf(
                    "X-Status" to status,
                    "X-Name" to (handshake.headers["x-name"] ?: "-"),
                    "X-Expire" to (handshake.headers["x-expire"] ?: "-"),
                    "X-Days-Left" to (handshake.headers["x-days-left"] ?: "-"),
                    "X-Premium" to (handshake.headers["x-premium"] ?: "-")
                )
            )
            sock.soTimeout = 0
            TunnelSessionStore.setState("CONNECTED")
            logger("Túnel abierto code=101 status=$status")
            sock
        } catch (e: Exception) {
            logger("ERROR abriendo túnel: ${e.message}")
            null
        }
    }

    private fun resolveStatusExhaustive(response: String, fallback: String?): String {
        val normalizedFallback = fallback?.trim()?.uppercase().orEmpty()
        if (normalizedFallback.isNotBlank() && normalizedFallback != "-") return normalizedFallback

        val statuses = Regex("(?im)^x-status\\s*:\\s*([^\\r\\n]+)")
            .findAll(response)
            .map { it.groupValues.getOrNull(1).orEmpty().trim().uppercase() }
            .filter { it.isNotBlank() }
            .toList()

        return statuses.firstOrNull { it in terminalStatuses }
            ?: statuses.firstOrNull { it == "OK" }
            ?: statuses.firstOrNull { it != "-" }
            ?: statuses.firstOrNull()
            ?: "UNKNOWN"
    }

    private data class HandshakeSocketResponse(val socket: Socket, val data: String)

    private fun runHandshakeRequest(
        action: String,
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ): HandshakeSocketResponse? {
        val sock = openProxySocket(protectSocket, logger) ?: return null
        return try {
            sock.tcpNoDelay = true
            val out = sock.getOutputStream()
            val inp = sock.getInputStream()

            val p1 = "GET / HTTP/1.1\r\nHost: $DECOY_HOST\r\n\r\n"
            out.write(p1.toByteArray())
            out.flush()
            logger("TX p1 host=$DECOY_HOST")
            logger("RAW TX p1:\n$p1")
            Thread.sleep(200)

            val p2 = "GET / HTTP/1.1\r\nHost: $selectedServerHost\r\nAction: $action\r\nAuth: $clientId\r\n\r\n"
            out.write(p2.toByteArray())
            out.flush()
            logger("TX p2 host=$selectedServerHost action=$action auth=${clientId.take(8)}***")
            logger("RAW TX p2:\n$p2")

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
            logger("RX $blocks bloques: ${resp.take(120)}")
            logger("RAW RX:\n$resp")
            HandshakeSocketResponse(sock, resp)
        } catch (e: Exception) {
            logger("ERROR handshake action=$action: ${e.message}")
            runCatching { sock.close() }
            null
        }
    }

    private fun openProxySocket(
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ): Socket? {
        if (selectedServerHost.isBlank()) {
            logger("ERROR host de servidor vacío")
            TunnelSessionStore.setState("ERROR")
            return null
        }
        val candidates = linkedSetOf<InetAddress>()
        DECOY_IPV6_CANDIDATES.forEach { preferredIpv6 ->
            runCatching { candidates += InetAddress.getByName(preferredIpv6) }
                .onFailure { logger("WARN IPv6 preferido inválido $preferredIpv6: ${it.javaClass.simpleName} ${it.message ?: ""}") }
        }
        runCatching { candidates += InetAddress.getByName(DECOY_IPV4) }
            .onFailure { logger("WARN IPv4 preferido inválido $DECOY_IPV4: ${it.javaClass.simpleName} ${it.message ?: ""}") }
        runCatching { candidates += InetAddress.getAllByName(DECOY_HOST).toList() }
            .onFailure { logger("WARN resolución DNS de proxy falló: ${it.javaClass.simpleName} ${it.message ?: ""}") }

        if (candidates.isEmpty()) {
            logger("ERROR no hay direcciones para proxy host=$DECOY_HOST")
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
                logger("Proxy conectado por ${if (address is java.net.Inet6Address) "IPv6" else "IPv4"} ${address.hostAddress}")
                return socket
            }.onFailure {
                logger("WARN fallo conexión proxy ${address.hostAddress}: ${it.javaClass.simpleName} ${it.message ?: ""}")
            }
        }

        logger("ERROR no se pudo conectar al proxy por IPv4/IPv6")
        TunnelSessionStore.setState("ERROR")
        return null
    }

    private data class HandshakeResult(
        val statusCode: Int,
        val headers: Map<String, String>
    )

    private fun pickHandshakeWithHeaders(response: String): HandshakeResult {
        val starts = Regex("HTTP/1\\.1\\s+\\d{3}").findAll(response).map { it.range.first }.toList()
        val candidates = starts.mapIndexed { index, start ->
            val end = starts.getOrNull(index + 1) ?: response.length
            val block = response.substring(start, end).trim()
            parseHandshakeBlock(block)
        }

        if (candidates.isEmpty()) {
            return HandshakeResult(-1, mapOf("x-status" to "UNKNOWN"))
        }

        val selected = candidates.firstOrNull {
            it.statusCode == 101 && it.headers["x-status"].isNullOrBlank().not()
        } ?: candidates.firstOrNull {
            it.headers["x-status"].isNullOrBlank().not()
        } ?: candidates.firstOrNull {
            it.statusCode == 101 && it.headers["upgrade"].equals("websocket", ignoreCase = true)
        } ?: candidates.lastOrNull {
            it.statusCode == 101
        } ?: candidates.last()

        val enrichedHeaders = selected.headers.toMutableMap()
        if (enrichedHeaders["x-status"].isNullOrBlank()) {
            enrichedHeaders["x-status"] = if (selected.statusCode == 101) "OK" else "UNKNOWN"
        }
        return HandshakeResult(selected.statusCode, enrichedHeaders)
    }

    private fun parseHandshakeBlock(block: String): HandshakeResult {
        val lines = block
            .replace("\r\n", "\n")
            .split('\n')
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
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
