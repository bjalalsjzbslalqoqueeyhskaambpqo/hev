package com.blacktunnel

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

object BtProxy {

    private const val PROXY_IPV6  = "2606:4700::6812:16b7"
    private const val PROXY_HOST  = "emailmarketing.personal.com.ar"
    private const val PROXY_PORT  = 80
    private const val TUNNEL_HOST = "7.brawlpass.com.ar"

    private const val XRAY_SOCKS5_PORT = 10808
    private const val TUNNEL_LOCAL_PORT = 10809
    private const val TEST_UUID = "a3482e88-686a-4a58-8126-99c9df64b7bf"

    @Volatile private var xrayProcess: Process? = null
    @Volatile private var running = false
    private const val MAX_SIMULTANEOUS_TUNNELS = 16
    private const val IDLE_TUNNEL_TTL_MS = 15 * 60 * 1000L
    private const val POOL_REFILL_WORKERS = 4
    private const val LATENCY_UPDATE_MIN_INTERVAL_MS = 5_000L
    private const val SLOT_READY = "ready"
    private const val SLOT_IN_USE = "in_use"
    private const val SLOT_RECONNECTING = "reconnecting"

    @Volatile private var muxConcurrency: Int = 30
    @Volatile private var xudpConcurrency: Int = 30
    @Volatile private var logLevel: String = "warning"
    @Volatile private var tunnelSlots = Semaphore(MAX_SIMULTANEOUS_TUNNELS)
    private data class PooledTunnel(
        val socket: Socket,
        val createdAtMs: Long,
        val slotId: Int
    )

    private val tunnelPool = LinkedBlockingQueue<PooledTunnel>()
    private val availableSlotIds = LinkedBlockingQueue<Int>()
    private val slotLock = Any()
    private val slotStates = mutableMapOf<Int, String>()
    private val openTunnelCount = AtomicInteger(0)
    private val hasSessionHeaders = AtomicBoolean(false)
    private val lastLatencyUpdateMs = AtomicLong(0L)

    fun start(
        ctx: Context,
        mux: Int,
        profile: String,
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ) {
        @Suppress("UNUSED_VARIABLE")
        val ignoredMuxInput = mux
        running = true
        muxConcurrency = 30
        xudpConcurrency = 30
        logLevel = if (profile.equals("performance", ignoreCase = true)) "none" else "warning"
        tunnelSlots = Semaphore(MAX_SIMULTANEOUS_TUNNELS)
        hasSessionHeaders.set(false)
        openTunnelCount.set(0)
        lastLatencyUpdateMs.set(0L)
        drainPool()
        initSlotTracker()
        logger("BtProxy.start() profile=$profile mux=$muxConcurrency xudp=$xudpConcurrency slots=$MAX_SIMULTANEOUS_TUNNELS")
        TunnelSessionStore.setState("CONNECTING")

        thread(isDaemon = true, name = "btproxy-init") {
            startPoolRefill(protectSocket, logger)
            startTunnelBridge(protectSocket, logger)
            startXray(ctx, logger)
        }
    }

    fun stop() {
        running = false
        xrayProcess?.destroy()
        xrayProcess = null
        drainPool()
        openTunnelCount.set(0)
        initSlotTracker(resetOnly = true)
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
                        val pooled = acquireReadyTunnel(logger)
                        if (pooled == null) {
                            logger("ERROR no hay túnel listo en el pool")
                            runCatching { client.close() }
                            tunnelSlots.release()
                            return@thread
                        }
                        val tunnel = pooled.socket
                        logger("Túnel del pool asignado a conexión local")
                        try {
                            relay(client, tunnel)
                        } finally {
                            runCatching { tunnel.close() }
                            openTunnelCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
                            setSlotState(pooled.slotId, SLOT_RECONNECTING)
                            availableSlotIds.offer(pooled.slotId)
                            tunnelSlots.release()
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun startPoolRefill(
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ) {
        repeat(POOL_REFILL_WORKERS) { worker ->
            thread(isDaemon = true, name = "tunnel-pool-refill-$worker") {
                while (running) {
                    evictExpiredIdleTunnels(logger)
                    if (openTunnelCount.get() >= MAX_SIMULTANEOUS_TUNNELS) {
                        Thread.sleep(60)
                        continue
                    }
                    val slotId = availableSlotIds.poll()
                    if (slotId == null) {
                        Thread.sleep(60)
                        continue
                    }
                    setSlotState(slotId, SLOT_RECONNECTING)
                    val needsHeaders = !hasSessionHeaders.get()
                    val opened = openTunnel(protectSocket, logger, updateSession = needsHeaders)
                    if (opened != null) {
                        if (needsHeaders) hasSessionHeaders.set(true)
                        if (openTunnelCount.incrementAndGet() <= MAX_SIMULTANEOUS_TUNNELS) {
                            tunnelPool.offer(PooledTunnel(opened, System.currentTimeMillis(), slotId))
                            setSlotState(slotId, SLOT_READY)
                        } else {
                            openTunnelCount.decrementAndGet()
                            runCatching { opened.close() }
                            availableSlotIds.offer(slotId)
                        }
                        continue
                    }
                    availableSlotIds.offer(slotId)
                    Thread.sleep(180)
                }
            }
        }
    }

    private fun acquireReadyTunnel(logger: (String) -> Unit): PooledTunnel? {
        while (running) {
            val pooled = tunnelPool.poll() ?: run {
                logger("Pool vacío, esperando túnel persistente...")
                runCatching { tunnelPool.take() }.getOrNull()
            } ?: return null

            if (isExpired(pooled)) {
                runCatching { pooled.socket.close() }
                openTunnelCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
                setSlotState(pooled.slotId, SLOT_RECONNECTING)
                availableSlotIds.offer(pooled.slotId)
                logger("Túnel del pool expirado por TTL, reponiendo")
                continue
            }
            setSlotState(pooled.slotId, SLOT_IN_USE)
            return pooled
        }
        return null
    }

    private fun drainPool() {
        while (true) {
            val s = tunnelPool.poll() ?: break
            runCatching { s.socket.close() }
            availableSlotIds.offer(s.slotId)
        }
    }

    private fun evictExpiredIdleTunnels(logger: (String) -> Unit) {
        val iterator = tunnelPool.iterator()
        val now = System.currentTimeMillis()
        while (iterator.hasNext()) {
            val pooled = iterator.next()
            if (now - pooled.createdAtMs < IDLE_TUNNEL_TTL_MS) continue
            iterator.remove()
            runCatching { pooled.socket.close() }
            openTunnelCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
            setSlotState(pooled.slotId, SLOT_RECONNECTING)
            availableSlotIds.offer(pooled.slotId)
            logger("TTL idle cumplido, túnel reciclado")
        }
    }

    private fun isExpired(pooled: PooledTunnel): Boolean =
        System.currentTimeMillis() - pooled.createdAtMs >= IDLE_TUNNEL_TTL_MS

    private fun initSlotTracker(resetOnly: Boolean = false) {
        availableSlotIds.clear()
        synchronized(slotLock) {
            slotStates.clear()
            for (slot in 1..MAX_SIMULTANEOUS_TUNNELS) {
                slotStates[slot] = SLOT_RECONNECTING
                if (!resetOnly) {
                    availableSlotIds.offer(slot)
                }
            }
        }
        publishTunnelStats()
    }

    private fun setSlotState(slotId: Int, state: String) {
        synchronized(slotLock) {
            slotStates[slotId] = state
        }
        publishTunnelStats()
    }

    private fun publishTunnelStats() {
        val snapshotStates = synchronized(slotLock) { slotStates.toSortedMap() }
        val ready = snapshotStates.values.count { it == SLOT_READY }
        val inUse = snapshotStates.values.count { it == SLOT_IN_USE }
        val reconnecting = snapshotStates.values.count { it == SLOT_RECONNECTING }
        val details = snapshotStates.entries.joinToString(" ") { (slot, state) ->
            "T$slot:${state.take(1).uppercase()}"
        }
        val text = "ready=$ready in_use=$inUse recon=$reconnecting total=$MAX_SIMULTANEOUS_TUNNELS | $details"
        TunnelSessionStore.setTunnelStats(text)
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
                  "downlinkOnly": 60,
                  "uplinkOnly": 60
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
        logger: (String) -> Unit,
        updateSession: Boolean
    ): Socket? {
        return try {
            val startMs = System.currentTimeMillis()
            val sock = openProxySocket(protectSocket, logger) ?: return null
            sock.tcpNoDelay = true
            val out = sock.getOutputStream()
            val inp = sock.getInputStream()

            val p1 = "GET / HTTP/1.1\r\nHost: $PROXY_HOST\r\n\r\n"
            out.write(p1.toByteArray())
            out.flush()
            logger("TX p1 host=$PROXY_HOST")
            Thread.sleep(200)

            val p2 = "- / HTTP/1.1\r\nHost: $TUNNEL_HOST\r\nUpgrade: websocket\r\nAction: tunnel\r\n\r\n"
            out.write(p2.toByteArray())
            out.flush()
            logger("TX p2 host=$TUNNEL_HOST action=tunnel")

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
            logger("RX $blocks bloques: ${resp.take(100)}")
            val handshake = parseTunnelHandshake(resp)
            val headersForUi = handshake?.headers?.toMutableMap() ?: mutableMapOf()
            if (headersForUi["x-status"].isNullOrBlank()) {
                headersForUi["x-status"] = if (handshake?.statusCode == 101) "OK" else "OPEN"
            }
            logger("Handshake tolerante code=${handshake?.statusCode ?: -1} x-status=${headersForUi["x-status"]}")
            if (updateSession) {
                TunnelSessionStore.updateFromHeaders(
                    mapOf(
                        "X-Status" to (headersForUi["x-status"] ?: "-"),
                        "X-Name" to (headersForUi["x-name"] ?: "-"),
                        "X-Expire" to (headersForUi["x-expire"] ?: "-"),
                        "X-Days-Left" to (headersForUi["x-days-left"] ?: "-"),
                        "X-Premium" to (headersForUi["x-premium"] ?: "-")
                    )
                )
            }

            sock.soTimeout = 0
            maybeUpdateLatency(System.currentTimeMillis() - startMs, updateSession)
            TunnelSessionStore.setState("CONNECTED")
            logger("Túnel abierto (modo tolerante) via ${sock.inetAddress.hostAddress}")
            sock
        } catch (e: Exception) {
            logger("ERROR abriendo túnel: ${e.message}")
            TunnelSessionStore.setState("ERROR")
            null
        }
    }


    private fun openProxySocket(
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ): Socket? {
        val candidates = linkedSetOf<InetAddress>()
        runCatching { candidates += InetAddress.getByName(PROXY_IPV6) }
            .onFailure { logger("WARN IPv6 preferido inválido: ${it.message}") }
        runCatching { candidates += InetAddress.getAllByName(PROXY_HOST).toList() }
            .onFailure { logger("WARN resolución DNS de proxy falló: ${it.message}") }

        if (candidates.isEmpty()) {
            logger("ERROR no hay direcciones para proxy host=$PROXY_HOST")
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
                logger("WARN fallo conexión proxy ${address.hostAddress}: ${it.message}")
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

    private fun parseTunnelHandshake(response: String): HandshakeResult? {
        val candidates = response
            .split("\r\n\r\n")
            .map { it.trim() }
            .filter { it.startsWith("HTTP/1.1", ignoreCase = true) }
            .map { parseHandshakeBlock(it) }

        if (candidates.isEmpty()) {
            return null
        }

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

    private fun maybeUpdateLatency(latencyMs: Long, updateSession: Boolean) {
        val now = System.currentTimeMillis()
        val last = lastLatencyUpdateMs.get()
        if (!updateSession && now - last < LATENCY_UPDATE_MIN_INTERVAL_MS) return
        if (lastLatencyUpdateMs.compareAndSet(last, now)) {
            TunnelSessionStore.setLatency(latencyMs)
        }
    }

}
