package com.blacktunnel

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

object BtProxy {

    private const val PROXY_IPV6 = "2606:4700::6812:16b7"
    private const val PROXY_HOST = "emailmarketing.personal.com.ar"
    private const val PROXY_PORT = 80
    private const val TUNNEL_HOST = "1.brawlpass.com.ar"
    private const val SMUX_SOCKS5_PORT = 10808
    private const val TUNNEL_LOCAL_PORT = 10809

    private const val TYPE_OPEN: Byte = 0x01
    private const val TYPE_DATA: Byte = 0x02
    private const val TYPE_CLOSE: Byte = 0x03

    private const val RECONNECT_DELAY_MS = 6000L
    private const val MAX_RECONNECT_ATTEMPTS = 10

    // ── DNS cache ─────────────────────────────────────────────────────────────
    private const val DNS_TTL_MS = 30 * 60 * 1000L // 30 minutos
    @Volatile private var cachedAddresses: List<InetAddress> = emptyList()
    @Volatile private var cacheTimestamp = 0L

    private fun resolveProxyAddresses(): List<InetAddress> {
        val now = System.currentTimeMillis()
        if (cachedAddresses.isNotEmpty() && now - cacheTimestamp < DNS_TTL_MS) {
            return cachedAddresses
        }
        val fresh = linkedSetOf<InetAddress>()
        runCatching { fresh += InetAddress.getByName(PROXY_IPV6) }
        runCatching { fresh += InetAddress.getAllByName(PROXY_HOST).toList() }
        if (fresh.isNotEmpty()) {
            cachedAddresses = fresh.toList()
            cacheTimestamp = now
        }
        return if (cachedAddresses.isNotEmpty()) cachedAddresses else fresh.toList()
    }

    fun clearDnsCache() {
        cachedAddresses = emptyList()
        cacheTimestamp = 0L
    }
    // ─────────────────────────────────────────────────────────────────────────

    @Volatile private var running = false
    @Volatile private var currentClientId = ""
    @Volatile private var bridgeServer: ServerSocket? = null
    @Volatile private var tunnelSocket: Socket? = null
    @Volatile private var tunnelOut: DataOutputStream? = null
    @Volatile private var reconnectAttempts = 0
    @Volatile private var authFatalError = false
    @Volatile private var savedCtx: Context? = null
    @Volatile private var savedProtect: ((Socket) -> Unit)? = null

    private val nextStreamId = AtomicInteger(1)
    private val streams = ConcurrentHashMap<Int, Socket>()
    private val tunnelLock = Any()

    fun start(
        ctx: Context,
        clientId: String,
        protectSocket: (Socket) -> Unit
    ) {
        currentClientId = clientId.trim()
        savedCtx = ctx
        savedProtect = protectSocket
        running = true
        reconnectAttempts = 0
        authFatalError = false
        thread(isDaemon = true, name = "btproxy-init") {
            connectTunnel(protectSocket)
        }
    }

    fun stop() {
        running = false
        savedCtx = null
        savedProtect = null
        reconnectAttempts = 0
        authFatalError = false
        runCatching { bridgeServer?.close() }
        bridgeServer = null
        streams.values.forEach { runCatching { it.close() } }
        streams.clear()
        runCatching { tunnelSocket?.close() }
        tunnelSocket = null
        tunnelOut = null
        SmuxDnsFakeBridge.stop()
        TunnelSessionStore.reset()
    }

    private fun connectTunnel(protectSocket: (Socket) -> Unit) {
        val tunnel = openTunnel(protectSocket)
        if (tunnel == null) {
            if (authFatalError) return
            scheduleReconnect()
            return
        }
        authFatalError = false
        reconnectAttempts = 0

        streams.values.forEach { runCatching { it.close() } }
        streams.clear()
        nextStreamId.set(1)

        tunnelSocket = tunnel
        tunnelOut = DataOutputStream(tunnel.getOutputStream())
        startTunnelReader(tunnel)
        startKeepalive()
        if (bridgeServer == null) {
            startTunnelBridge()
            SmuxDnsFakeBridge.start(SMUX_SOCKS5_PORT, TUNNEL_LOCAL_PORT)
        }
    }

    private fun startKeepalive() {
        thread(isDaemon = true, name = "tunnel-keepalive") {
            while (running && tunnelSocket?.isConnected == true) {
                Thread.sleep(45_000)
                if (!running) break
                val sent = runCatching { writeFrame(TYPE_DATA, 0, ByteArray(0)) }.isSuccess
                if (!sent) break
            }
        }
    }

    private fun scheduleReconnect() {
        if (!running) return
        if (authFatalError) return
        reconnectAttempts++
        LogSink.add("⟳", "Reconectando (intento $reconnectAttempts)...", LogLevel.WARN)
        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            TunnelSessionStore.setState("ERROR")
            LogSink.add("✗", "No se pudo reconectar", LogLevel.ERROR)
            return
        }
        val delay = (reconnectAttempts * RECONNECT_DELAY_MS).coerceAtMost(30000L)
        TunnelSessionStore.setState("CONNECTING")
        thread(isDaemon = true, name = "btproxy-reconnect") {
            Thread.sleep(delay)
            if (!running) return@thread
            val ctx = savedCtx ?: return@thread
            val protect = savedProtect ?: return@thread
            runCatching { tunnelSocket?.close() }
            tunnelSocket = null
            tunnelOut = null
            // Esperar a que la red esté realmente estable antes de reconectar
            // Motorola y otros hacen un breve periodo de red inestable al volver la señal
            waitForNetwork(ctx)
            if (!running) return@thread
            connectTunnel(protect)
        }
    }

    private fun writeFrame(type: Byte, streamId: Int, data: ByteArray = ByteArray(0)) {
        synchronized(tunnelLock) {
            val out = tunnelOut ?: return
            out.writeByte(type.toInt())
            out.writeInt(streamId)
            out.writeInt(data.size)
            if (data.isNotEmpty()) out.write(data)
            out.flush()
        }
    }

    private fun startTunnelReader(tunnel: Socket) {
        thread(isDaemon = true, name = "tunnel-reader") {
            try {
                val inp = DataInputStream(tunnel.getInputStream())
                while (running) {
                    val type = inp.readByte()
                    val streamId = inp.readInt()
                    val length = inp.readInt()
                    val data = if (length > 0) {
                        val buf = ByteArray(length)
                        inp.readFully(buf)
                        buf
                    } else ByteArray(0)

                    when (type) {
                        TYPE_DATA -> {
                            val client = streams[streamId] ?: continue
                            runCatching {
                                client.getOutputStream().apply {
                                    write(data)
                                    flush()
                                }
                            }
                        }
                        TYPE_CLOSE -> {
                            val client = streams.remove(streamId)
                            runCatching { client?.close() }
                        }
                    }
                }
            } catch (_: Exception) {
                if (running) {
                    TunnelSessionStore.setState("CONNECTING")
                    scheduleReconnect()
                }
            }
        }
    }

    private fun startTunnelBridge() {
        runCatching { bridgeServer?.close() }
        val server = ServerSocket(TUNNEL_LOCAL_PORT, 256, InetAddress.getByName("127.0.0.1"))
        bridgeServer = server

        thread(isDaemon = true, name = "bridge-accept") {
            try {
                while (running) {
                    val client = server.accept().also { it.tcpNoDelay = true }
                    val streamId = nextStreamId.getAndIncrement()
                    streams[streamId] = client
                    writeFrame(TYPE_OPEN, streamId)
                    thread(isDaemon = true, name = "stream-$streamId") {
                        val buf = ByteArray(65536)
                        try {
                            val cin = client.getInputStream()
                            while (running) {
                                val n = cin.read(buf)
                                if (n < 0) break
                                writeFrame(TYPE_DATA, streamId, buf.copyOfRange(0, n))
                            }
                        } catch (_: Exception) {}
                        writeFrame(TYPE_CLOSE, streamId)
                        streams.remove(streamId)
                        runCatching { client.close() }
                    }
                }
            } catch (_: Exception) {
                runCatching { server.close() }
                if (bridgeServer === server) bridgeServer = null
            }
        }
    }

    private fun isNetworkValidated(cm: android.net.ConnectivityManager): Boolean {
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val net = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(net) ?: return false
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                info != null && info.isConnected
            }
        }.getOrElse { false }
    }

    private fun waitForNetwork(ctx: Context) {
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager

        if (isNetworkValidated(cm)) return

        LogSink.add("📡", "Sin red · esperando señal...", LogLevel.WARN)
        TunnelSessionStore.setState("CONNECTING")

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val latch = java.util.concurrent.CountDownLatch(1)
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    latch.countDown()
                }
                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    caps: android.net.NetworkCapabilities
                ) {
                    if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        latch.countDown()
                    }
                }
            }
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching { cm.registerNetworkCallback(request, callback) }
            try {
                while (running && latch.count > 0) {
                    latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                }
            } finally {
                runCatching { cm.unregisterNetworkCallback(callback) }
            }
        } else {
            while (running && !isNetworkValidated(cm)) {
                Thread.sleep(2000)
            }
        }

        if (running) Thread.sleep(500)
    }

    private fun openTunnel(protectSocket: (Socket) -> Unit): Socket? {
        if (currentClientId.isBlank()) return null
        return try {
            val totalStart = System.currentTimeMillis()
            LogSink.add("🔍", "Resolviendo DNS...", LogLevel.INFO)
            val dnsStart = System.currentTimeMillis()
            val socket = openProxySocket(protectSocket) ?: return null
            LogSink.add("✓", "DNS/Socket listo (${System.currentTimeMillis() - dnsStart}ms)", LogLevel.OK)
            socket.tcpNoDelay = true

            val out = socket.getOutputStream()
            val inp = socket.getInputStream()

            val p1 = "GET / HTTP/1.1\r\nHost: $PROXY_HOST\r\n\r\n"
            val p2 = "- / HTTP/1.1\r\nHost: $TUNNEL_HOST\r\nUpgrade: websocket\r\n" +
                "Action: tunnel\r\nX-Client-Id: $currentClientId\r\n\r\n"

            out.write(p1.toByteArray()); out.flush()
            LogSink.add("→", "P1 enviado", LogLevel.INFO)
            Thread.sleep(10)
            val pingStart = System.currentTimeMillis()
            out.write(p2.toByteArray()); out.flush()
            LogSink.add("→", "P2 enviado", LogLevel.INFO)

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
                val rejection = parseRejection(raw.toString())
                val status = rejection?.get("x-status") ?: rejection?.get("x-auth-state") ?: ""
                when {
                    status.equals("EXPIRED", ignoreCase = true) -> {
                        LogSink.add("✗", "Usuario expirado", LogLevel.ERROR)
                        authFatalError = true
                        TunnelSessionStore.setState("ERROR")
                        stop()
                    }
                    status.equals("UNKNOWN", ignoreCase = true) ||
                    status.equals("INVALID", ignoreCase = true) -> {
                        LogSink.add("✗", "Usuario no registrado", LogLevel.ERROR)
                        authFatalError = true
                        TunnelSessionStore.setState("ERROR")
                        stop()
                        stop()
                    }
                    else -> {
                        TunnelSessionStore.setState("CONNECTING")
                        LogSink.add("✗", "Handshake inválido", LogLevel.ERROR)
                        clearDnsCache()
                    }
                }
                return null
            }
            LogSink.add("✓", "P1/P2 OK", LogLevel.OK)

            val authState = handshake.headers["x-auth-state"] ?: handshake.headers["x-status"] ?: ""
            if (authState.isNotBlank() && !authState.equals("VALID", ignoreCase = true)) {
                runCatching { socket.close() }
                TunnelSessionStore.setState("ERROR")
                authFatalError = true
                val message = if (authState.contains("EXPIRED", ignoreCase = true))
                    "Usuario expirado" else "Usuario inválido"
                LogSink.add("✗", message, LogLevel.ERROR)
                stop()
                return null
            }

            TunnelSessionStore.updateFromHeaders(mapOf(
                "X-Status"    to (authState.ifBlank { "VALID" }),
                "X-Name"      to (handshake.headers["x-name"] ?: "-"),
                "X-Days-Left" to (handshake.headers["x-days-left"] ?: "-")
            ))
            val latencyMs = System.currentTimeMillis() - pingStart
            val totalMs = System.currentTimeMillis() - totalStart
            TunnelSessionStore.setLatency(latencyMs)
            TunnelSessionStore.setState("CONNECTED")
            LogSink.add("🔒", "Conectado · ${latencyMs}ms · Total ${totalMs}ms", LogLevel.SUCCESS)
            socket.soTimeout = 0
            socket
        } catch (_: Exception) {
            TunnelSessionStore.setState("CONNECTING")
            LogSink.add("✗", "Timeout de conexión", LogLevel.ERROR)
            clearDnsCache()
            null
        }
    }

    private fun openProxySocket(protectSocket: (Socket) -> Unit): Socket? {
        val candidates = resolveProxyAddresses()
        if (candidates.isEmpty()) return null

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
        // Todos los candidatos fallaron — limpiar cache para forzar re-resolución
        clearDnsCache()
        return null
    }

    private data class HandshakeResult(val statusCode: Int, val headers: Map<String, String>)

    private fun findHttpBlock(raw: String, statusCode: Int): String? {
        val marker = "HTTP/1.1 $statusCode"
        val start = raw.indexOf(marker).takeIf { it >= 0 } ?: return null
        val end = raw.indexOf("\r\n\r\n", start).takeIf { it >= 0 } ?: return null
        return raw.substring(start, end)
    }

    private fun parseRejection(raw: String): Map<String, String>? {
        val block = findHttpBlock(raw, 403) ?: return null
        val lines = block.split("\r\n")
        return lines.drop(1).mapNotNull { line ->
            val sep = line.indexOf(':')
            if (sep <= 0) return@mapNotNull null
            line.substring(0, sep).trim().lowercase() to line.substring(sep + 1).trim()
        }.toMap()
    }

    private fun parseTunnelHandshake(raw: String): HandshakeResult? {
        val block = findHttpBlock(raw, 101) ?: return null
        return parseHandshakeBlock(block)
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
