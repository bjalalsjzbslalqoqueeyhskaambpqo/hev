package com.nexora

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.VpnService
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.system.OsConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

// ===== from app/src/main/java/com/blacktunnel/BootReceiver.kt =====
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.ACTION_BOOT_COMPLETED"
        )
        if (intent.action !in validActions) return
        if (!TunnelPrefs.wasConnected(context)) return

        val vpnIntent = Intent(context, BtVpnService::class.java).setAction(BtVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(vpnIntent)
        else context.startService(vpnIntent)
    }
}


// ===== from app/src/main/java/com/blacktunnel/BtProxy.kt =====
object BtProxy {

    private const val PROXY_IPV6 = "2606:4700::6812:16b7"
    private const val PROXY_HOST = "emailmarketing.personal.com.ar"
    private const val PROXY_PORT = 80
    private const val TUNNEL_HOST = "1.brawlpass.com.ar"
    private const val XRAY_SOCKS5_PORT = 10808
    private const val TUNNEL_LOCAL_PORT = 10809
    private const val TEST_UUID = "a3482e88-686a-4a58-8126-99c9df64b7bf"

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
    @Volatile private var xrayProcess: Process? = null
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
            connectTunnel(ctx, protectSocket)
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
        xrayProcess?.let { process ->
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
        xrayProcess = null
        TunnelSessionStore.reset()
    }

    private fun connectTunnel(ctx: Context, protectSocket: (Socket) -> Unit) {
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
        startTunnelReader(tunnel, ctx, protectSocket)
        startKeepalive()
        if (bridgeServer == null) {
            startTunnelBridge()
            startXray(ctx)
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
            connectTunnel(ctx, protect)
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

    private fun startTunnelReader(tunnel: Socket, ctx: Context, protectSocket: (Socket) -> Unit) {
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
          "dns": {
            "servers": [
              "fakedns",
              { "address": "8.8.8.8", "queryStrategy": "UseIPv4" },
              { "address": "1.1.1.1", "queryStrategy": "UseIPv4" }
            ],
            "queryStrategy": "UseIPv4",
            "disableCache": false,
            "disableFallback": false
          },
          "fakedns": [{ "ipPool": "198.18.0.0/15", "poolSize": 65535 }],
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
              "udpTimeout": 0,
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
              "settings": { "udp": true },
              "sniffing": {
                "enabled": true,
                "destOverride": ["http", "tls", "quic", "fakedns"],
                "metadataOnly": false
              }
            }${buildHotspotInbound(ctx)}
          ],
          "outbounds": [
            {
              "protocol": "vless",
              "settings": {
                "vnext": [{
                  "address": "127.0.0.1",
                  "port": $TUNNEL_LOCAL_PORT,
                  "users": [{ "id": "$TEST_UUID", "encryption": "none" }]
                }]
              },
              "streamSettings": { "network": "tcp", "security": "none" },
              "mux": {
                "enabled": true,
                "concurrency": 128,
                "xudpConcurrency": 1024,
                "xudpProxyUDP443": "allow"
              },
              "targetStrategy": "UseIPv4"
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
              "settings": { "udp": true, "ip": "$ip" },
              "sniffing": {
                "enabled": true,
                "destOverride": ["http", "tls", "quic", "fakedns"],
                "metadataOnly": false
              }
            },
            {
              "protocol": "http",
              "listen": "0.0.0.0",
              "port": 8282,
              "settings": {},
              "sniffing": {
                "enabled": true,
                "destOverride": ["http", "tls", "fakedns"],
                "metadataOnly": false
              }
            }"""
    }

    fun getHotspotIp(): String? = runCatching {
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


// ===== from app/src/main/java/com/blacktunnel/BtVpnService.kt =====
class BtVpnService : VpnService() {

    private var pfd: ParcelFileDescriptor? = null
    private var rawTunFd: Int? = null
    @Volatile private var isStopping = false
    @Volatile private var desiredRunning = false
    private var networkReceiverRegistered = false
    private val networkChangeReceiver = NetworkChangeReceiver()

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("BTCRASH", "Crash en ${thread.name}: ${throwable.message}")
            if (desiredRunning && !isStopping) {
                val restartIntent = Intent(applicationContext, BtVpnService::class.java)
                    .setAction(ACTION_START)
                val pending = PendingIntent.getService(
                    applicationContext, 99, restartIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
                alarm.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 3000,
                    pending
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return runCatching {
            when (intent?.action) {
                ACTION_STOP -> {
                    desiredRunning = false
                    TunnelPrefs.setWasConnected(this, false)
                    stopTunnel()
                    START_NOT_STICKY
                }
                else -> {
                    desiredRunning = true
                    TunnelPrefs.setWasConnected(this, true)
                    startTunnel()
                    START_STICKY
                }
            }
        }.getOrElse {
            android.util.Log.e("BTCRASH", "onStartCommand crash: ${it.message}")
            START_STICKY
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching { super.onTaskRemoved(rootIntent) }
        if (!desiredRunning) return
        runCatching {
            val restartIntent = Intent(applicationContext, BtVpnService::class.java)
                .setAction(ACTION_START)
            val pending = PendingIntent.getService(
                this, 1, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
            alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000, pending)
        }
    }

    override fun onDestroy() {
        runCatching {
            if (desiredRunning) {
                val restartIntent = Intent(applicationContext, BtVpnService::class.java)
                    .setAction(ACTION_START)
                val pending = PendingIntent.getService(
                    this, 2, restartIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
                alarm.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 2000,
                    pending
                )
            }
        }
        runCatching { stopTunnel() }
        runCatching { super.onDestroy() }
    }

    private fun startTunnel() {
        runCatching {
            isStopping = false

            if (pfd != null) {
                TunnelSessionStore.setState("CONNECTED")
                return
            }

            TunnelSessionStore.setState("CONNECTING")
            startVpnForeground()

            thread(isDaemon = true, name = "vpn-start-sequence") {
                runCatching {
                    val clientId = TunnelPrefs.getOrCreateClientId(this@BtVpnService)

                    val builder = Builder()
                        .setSession("Nexora")
                        .addAddress("198.18.0.1", 30)
                        .addAddress("fc00::1", 126)
                        .addRoute("0.0.0.0", 0)
                        .addRoute("::", 0)
                        .addDnsServer("8.8.8.8")
                        .addDnsServer("1.1.1.1")
                        .addDnsServer("2001:4860:4860::8888")
                        .addDnsServer("2606:4700:4700::1111")
                        .setMtu(1300)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        runCatching { builder.allowFamily(OsConstants.AF_INET) }
                        runCatching { builder.allowFamily(OsConstants.AF_INET6) }
                    }

                    runCatching { configureAllowedApplications(builder) }

                    val established = runCatching { builder.establish() }.getOrNull()
                    if (established == null) {
                        TunnelSessionStore.setState("ERROR")
                        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                        runCatching { stopSelf() }
                        return@runCatching
                    }

                    pfd = established
                    runCatching { registerNetworkReceiver() }

                    BtProxy.start(
                        ctx = this@BtVpnService,
                        clientId = clientId,
                        protectSocket = { socket -> runCatching { protect(socket) } }
                    )

                    val rawFd = runCatching {
                        ParcelFileDescriptor.dup(established.fileDescriptor).detachFd()
                    }.getOrNull()

                    if (rawFd == null) {
                        TunnelSessionStore.setState("ERROR")
                        return@runCatching
                    }

                    rawTunFd = rawFd
                    val configFile = runCatching { writeHevConfig() }.getOrNull() ?: return@runCatching

                    thread(isDaemon = true, name = "hev-main") {
                        runCatching { HevBridge.start(configFile.absolutePath, rawFd) }
                        runCatching { closeRawTunFd() }
                    }
                }.onFailure { e ->
                    android.util.Log.e("BTCRASH", "vpn-start-sequence crash: ${e.message}")
                    TunnelSessionStore.setState("ERROR")
                }
            }
        }.onFailure { e ->
            android.util.Log.e("BTCRASH", "startTunnel crash: ${e.message}")
        }
    }

    private fun startVpnForeground() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    VPN_CHANNEL_ID, "Nexora VPN",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Servicio VPN activo"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(channel)
            }
            val openAppIntent = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            val disconnectIntent = PendingIntent.getService(
                this, 0,
                Intent(this, BtVpnService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, VPN_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Nexora activo")
                .setContentText("Conexión protegida")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openAppIntent)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Desconectar", disconnectIntent
                )
                .build()
            startForeground(VPN_NOTIFICATION_ID, notification)
        }
    }

    private fun stopTunnel() {
        if (isStopping) return
        isStopping = true
        runCatching { HevBridge.stop() }
        runCatching { BtProxy.stop() }
        runCatching { closeRawTunFd() }
        runCatching { pfd?.close() }
        pfd = null
        runCatching {
            if (networkReceiverRegistered) {
                unregisterReceiver(networkChangeReceiver)
                networkReceiverRegistered = false
            }
        }
        runCatching { TunnelSessionStore.setState("DISCONNECTED") }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
    }

    private fun registerNetworkReceiver() {
        if (networkReceiverRegistered) return
        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        registerReceiver(networkChangeReceiver, filter)
        networkReceiverRegistered = true
    }

    private fun closeRawTunFd() {
        val fd = rawTunFd ?: return
        rawTunFd = null
        runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
    }

    private fun configureAllowedApplications(builder: Builder) {
        val profile = TunnelPrefs.getProfile(this)
        val includedApps = TunnelPrefs.getIncludedApps(this)
        if (!profile.equals("performance", ignoreCase = true)) {
            runCatching { builder.addDisallowedApplication(packageName) }
            return
        }
        if (includedApps.isEmpty()) {
            packageManager.getInstalledApplications(0)
                .map { it.packageName }
                .filter { it != packageName }
                .forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
            return
        }
        includedApps.forEach { pkg -> runCatching { builder.addAllowedApplication(pkg) } }
    }

    private fun writeHevConfig(): java.io.File {
        val file = java.io.File(filesDir, "hev.yml")
        file.writeText(
            """
            tunnel:
              name: trehev
              mtu: 1300
              ipv4: 198.18.0.1
              ipv6: fc00::1
            socks5:
              address: 127.0.0.1
              port: 10808
              udp: 'udp'
            misc:
              log-level: warn
            """.trimIndent()
        )
        return file
    }

    companion object {
        const val ACTION_START = "com.nexora.START"
        const val ACTION_STOP = "com.nexora.STOP"
        private const val VPN_CHANNEL_ID = "vpn_channel"
        private const val VPN_NOTIFICATION_ID = 1
    }
}


// ===== from app/src/main/java/com/blacktunnel/BtWifiDirect.kt =====
object BtWifiDirect {

    private const val GATEWAY_IP = "192.168.49.1"
    private const val SOCKS5_PORT = 1080
    private const val HTTP_PORT = 8282

    @Volatile
    var isActive = false
        private set

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    fun getSavedPassword(ctx: Context): String {
        return ctx.getSharedPreferences("bt_prefs", Context.MODE_PRIVATE)
            .getString("wifidirect_pass", "12345678") ?: "12345678"
    }

    fun savePassword(ctx: Context, password: String) {
        ctx.getSharedPreferences("bt_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("wifidirect_pass", password)
            .apply()
    }

    fun start(ctx: Context, onResult: (success: Boolean) -> Unit) {
        val appCtx = ctx.applicationContext
        manager = appCtx.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager?.initialize(appCtx, appCtx.mainLooper, null)
        val activeChannel = channel ?: run {
            isActive = false
            onResult(false)
            return
        }

        receiver?.let {
            runCatching { appCtx.unregisterReceiver(it) }
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                    manager?.requestGroupInfo(activeChannel) { group ->
                        isActive = group != null && group.isGroupOwner
                    }
                }
            }
        }
        appCtx.registerReceiver(receiver, IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val config = WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-Nexora")
                .setPassphrase(getSavedPassword(appCtx))
                .build()
            manager?.createGroup(activeChannel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    isActive = true
                    onResult(true)
                }

                override fun onFailure(reason: Int) {
                    isActive = false
                    onResult(false)
                }
            })
        } else {
            manager?.createGroup(activeChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    isActive = true
                    onResult(true)
                }

                override fun onFailure(reason: Int) {
                    isActive = false
                    onResult(false)
                }
            })
        }
    }

    fun stop(ctx: Context) {
        val appCtx = ctx.applicationContext
        val activeChannel = channel
        if (activeChannel != null) manager?.removeGroup(activeChannel, null)
        receiver?.let { runCatching { appCtx.unregisterReceiver(it) } }
        receiver = null
        isActive = false
    }

    fun getConnectionInfo(ctx: Context) = mapOf(
        "ssid" to "DIRECT-Nexora",
        "password" to getSavedPassword(ctx),
        "ip" to GATEWAY_IP,
        "socks5" to SOCKS5_PORT,
        "http" to HTTP_PORT
    )
}


// ===== from app/src/main/java/com/blacktunnel/HevBridge.kt =====
object HevBridge {
    init {
        System.loadLibrary("hev-jni")
    }

    external fun start(configPath: String, tunFd: Int): Int
    external fun stop()
}


// ===== from app/src/main/java/com/blacktunnel/LogSink.kt =====
object LogSink {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries = _entries.asStateFlow()

    fun clear() {
        _entries.value = emptyList()
    }

    fun add(icon: String, text: String, level: LogLevel = LogLevel.INFO) {
        val entry = LogEntry(icon = icon, text = text, color = level.color)
        _entries.value = (_entries.value + entry).takeLast(10)
    }
}

enum class LogLevel(val color: androidx.compose.ui.graphics.Color) {
    INFO(androidx.compose.ui.graphics.Color(0xFF94A3B8)),
    OK(androidx.compose.ui.graphics.Color(0xFFE2E8F0)),
    SUCCESS(androidx.compose.ui.graphics.Color(0xFF4ADE80)),
    WARN(androidx.compose.ui.graphics.Color(0xFFFBBF24)),
    ERROR(androidx.compose.ui.graphics.Color(0xFFFF4C6A))
}


// ===== from app/src/main/java/com/blacktunnel/MainActivity.kt =====
class MainActivity : ComponentActivity() {

    private val logVm: LogViewModel by viewModels()
    private var pendingWifiDirectStart = false
    private val wifiDirectEnabledState = mutableStateOf(BtWifiDirect.isActive)
    private val wifiDirectPasswordState = mutableStateOf("")

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startVpn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifiDirectPasswordState.value = BtWifiDirect.getSavedPassword(this)
        TunnelPrefs.setProfile(this, "normal")
        requestBatteryExemptionIfNeeded()

        setContent {
            NexoraTheme {
                val session by TunnelSessionStore.stateFlow.collectAsStateWithLifecycle()
                val logEntries by logVm.entries.collectAsStateWithLifecycle()

                val vpnState = when (session.state) {
                    "CONNECTED" -> VpnState.CONNECTED
                    "CONNECTING" -> VpnState.CONNECTING
                    "ERROR" -> VpnState.ERROR
                    else -> VpnState.IDLE
                }

                var isHotspot by rememberSaveable { mutableStateOf(TunnelPrefs.isHotspotProxyEnabled(this)) }
                var isWifiDirect by wifiDirectEnabledState
                var wifiPass by wifiDirectPasswordState

                MainScreen(
                    state = vpnState,
                    clientName = session.name,
                    daysLeft = session.daysLeft,
                    latencyMs = session.latencyMs,
                    status = session.status,
                    connectedSince = session.connectedSince,
                    logEntries = logEntries,
                    onConnectClick = {
                        if (vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING) {
                            stopVpn()
                            logVm.add("⏹", "Desconectando túnel", LogLevel.INFO)
                        } else {
                            logVm.clear()
                            logVm.add("▶", "Iniciando túnel", LogLevel.INFO)
                            startVpnWithPermission()
                        }
                    },
                    onCopyClientId = { copyClientId() },
                    isHotspotEnabled = isHotspot,
                    onHotspotToggle = { enabled ->
                        val ip = BtProxy.getHotspotIp()
                        if (enabled && ip == null) {
                            Toast.makeText(this, getString(R.string.hotspot_enable_first), Toast.LENGTH_SHORT).show()
                        } else {
                            isHotspot = enabled
                            TunnelPrefs.setHotspotProxyEnabled(this, enabled)
                        }
                    },
                    hotspotIp = BtProxy.getHotspotIp(),
                    isWifiDirectEnabled = isWifiDirect,
                    onWifiDirectToggle = { enabled ->
                        if (enabled) {
                            if (wifiPass.length < WIFI_DIRECT_MIN_PASSWORD_LEN) {
                                Toast.makeText(this, getString(R.string.wifi_direct_password_min), Toast.LENGTH_SHORT).show()
                            } else {
                                BtWifiDirect.savePassword(this, wifiPass)
                                requestWifiDirectPermissionOrStart {
                                    BtWifiDirect.start(this) { ok -> isWifiDirect = ok }
                                }
                            }
                        } else {
                            BtWifiDirect.stop(this)
                            isWifiDirect = false
                        }
                    },
                    wifiDirectPassword = wifiPass,
                    onWifiDirectPasswordChange = { pwd ->
                        wifiPass = pwd
                        if (pwd.length >= WIFI_DIRECT_MIN_PASSWORD_LEN) BtWifiDirect.savePassword(this, pwd)
                    },
                    onIgnoreBatteryClick = {
                        val requested = requestBatteryOptimizationExemption()
                        if (!requested) openBatterySettings()
                    }
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_WIFI_DIRECT_PERMISSION) return

        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted && pendingWifiDirectStart) {
            pendingWifiDirectStart = false
            BtWifiDirect.start(this) { ok -> wifiDirectEnabledState.value = ok }
            return
        }

        pendingWifiDirectStart = false
        Toast.makeText(this, getString(R.string.wifi_direct_permission_required), Toast.LENGTH_SHORT).show()
    }

    private fun startVpnWithPermission() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
            return
        }
        startVpn()
    }

    private fun startVpn() {
        TunnelSessionStore.setState("CONNECTING")
        startService(Intent(this, BtVpnService::class.java).setAction(BtVpnService.ACTION_START))
    }

    private fun stopVpn() {
        startService(Intent(this, BtVpnService::class.java).setAction(BtVpnService.ACTION_STOP))
    }

    private fun copyClientId() {
        val clientId = TunnelPrefs.getOrCreateClientId(this)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("client_id", clientId))
        Toast.makeText(this, getString(R.string.client_id_copied), Toast.LENGTH_SHORT).show()
    }

    private fun requestWifiDirectPermissionOrStart(onGranted: () -> Unit) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        val granted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            onGranted()
            return
        }
        pendingWifiDirectStart = true
        requestPermissions(arrayOf(permission), REQ_WIFI_DIRECT_PERMISSION)
    }

    private fun requestBatteryOptimizationExemption(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return false

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        return runCatching { startActivity(intent); true }.getOrDefault(false)
    }

    private fun openBatterySettings() {
        val intents = listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            },
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )

        val opened = intents.firstOrNull { intent ->
            intent.resolveActivity(packageManager) != null &&
                runCatching { startActivity(intent); true }.getOrDefault(false)
        }
        if (opened == null) Toast.makeText(this, getString(R.string.battery_settings_failed), Toast.LENGTH_SHORT).show()
    }

    private fun requestBatteryExemptionIfNeeded() {
        if (TunnelPrefs.isOnboardingShown(this)) return
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Mantener conexión activa")
                .setMessage(getBatteryInstructionsByBrand())
                .setPositiveButton("Configurar ahora") { _, _ ->
                    startActivity(Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    ))
                    TunnelPrefs.setOnboardingShown(this)
                }
                .setNegativeButton("Después", null)
                .show()
        } else {
            TunnelPrefs.setOnboardingShown(this)
        }
    }

    private fun getBatteryInstructionsByBrand(): String {
        return when (Build.MANUFACTURER.lowercase()) {
            "motorola" -> "Ajustes → Batería → Gestión de batería → Nexora → Sin restricciones"
            "xiaomi", "redmi", "poco" -> "Ajustes → Aplicaciones → Nexora → Batería → Sin restricciones"
            "samsung" -> "Ajustes → Batería → Optimización de batería → Nexora → No optimizar"
            "huawei", "honor" -> "Ajustes → Batería → Inicio de aplicaciones → Nexora → Activar manualmente"
            "oppo", "realme", "oneplus" -> "Ajustes → Batería → Optimización de batería → Nexora → No optimizar"
            else -> "Ajustes → Batería → Optimización de batería → Nexora → No optimizar"
        }
    }

    companion object {
        private const val WIFI_DIRECT_MIN_PASSWORD_LEN = 8
        private const val REQ_WIFI_DIRECT_PERMISSION = 3101
    }
}


// ===== from app/src/main/java/com/blacktunnel/NetworkChangeReceiver.kt =====
class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!TunnelPrefs.wasConnected(context)) return

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val isConnected = cm.activeNetworkInfo?.isConnected == true
        if (!isConnected) return

        val vpnIntent = Intent(context, BtVpnService::class.java).setAction(BtVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(vpnIntent)
        else context.startService(vpnIntent)
    }
}


// ===== from app/src/main/java/com/blacktunnel/TunnelPrefs.kt =====
object TunnelPrefs {
    private const val PREFS = "tunnel_prefs"
    private const val KEY_PROFILE = "profile"
    private const val KEY_INCLUDED_APPS = "included_apps"
    private const val KEY_CLIENT_ID = "client_id"
    private const val KEY_HOTSPOT_PROXY = "hotspot_proxy"

    fun getProfile(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PROFILE, "normal") ?: "normal"

    fun setProfile(ctx: Context, profile: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PROFILE, profile).apply()
    }

    fun getIncludedApps(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_INCLUDED_APPS, "")
            .orEmpty()
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    fun setIncludedApps(ctx: Context, packages: List<String>) {
        val raw = packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_INCLUDED_APPS, raw).apply()
    }

    fun getOrCreateClientId(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_CLIENT_ID, "").orEmpty().trim()
        if (current.isNotEmpty()) return current

        val generated = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_CLIENT_ID, generated).apply()
        return generated
    }

    fun isHotspotProxyEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HOTSPOT_PROXY, false)

    fun setHotspotProxyEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HOTSPOT_PROXY, enabled).apply()
    }

    fun setWasConnected(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("was_connected", value).apply()
    }

    fun wasConnected(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("was_connected", false)

    fun setOnboardingShown(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("onboarding_shown", true).apply()
    }

    fun isOnboardingShown(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("onboarding_shown", false)

}


// ===== from app/src/main/java/com/blacktunnel/TunnelSessionStore.kt =====
data class TunnelSessionSnapshot(
    val state: String = "DISCONNECTED",
    val status: String = "-",
    val name: String = "-",
    val daysLeft: String = "-",
    val latencyMs: Long = -1L,
    val connectedSince: Long = 0L
)

object TunnelSessionStore {
    private val lock = Any()
    private var snapshot = TunnelSessionSnapshot()
    private val _stateFlow = MutableStateFlow(snapshot)
    val stateFlow: StateFlow<TunnelSessionSnapshot> = _stateFlow.asStateFlow()
    private val listeners = mutableSetOf<(TunnelSessionSnapshot) -> Unit>()

    fun setState(state: String) {
        synchronized(lock) {
            snapshot = snapshot.copy(
                state = state,
                connectedSince = when (state) {
                    "CONNECTED" -> System.currentTimeMillis()
                    "CONNECTING" -> 0L
                    "DISCONNECTED", "ERROR" -> 0L
                    else -> snapshot.connectedSince
                }
            )
        }
        notifyListeners()
    }

    fun setLatency(latencyMs: Long) {
        synchronized(lock) { snapshot = snapshot.copy(latencyMs = latencyMs) }
        notifyListeners()
    }

    fun updateFromHeaders(headers: Map<String, String>) {
        synchronized(lock) {
            snapshot = snapshot.copy(
                status = headers["X-Status"] ?: snapshot.status,
                name = headers["X-Name"] ?: snapshot.name,
                daysLeft = headers["X-Days-Left"] ?: snapshot.daysLeft
            )
        }
        notifyListeners()
    }

    fun reset() {
        synchronized(lock) { snapshot = TunnelSessionSnapshot() }
        notifyListeners()
    }

    fun current(): TunnelSessionSnapshot = synchronized(lock) { snapshot }

    fun addListener(listener: (TunnelSessionSnapshot) -> Unit) {
        synchronized(lock) { listeners.add(listener) }
        listener(current())
    }

    fun removeListener(listener: (TunnelSessionSnapshot) -> Unit) {
        synchronized(lock) { listeners.remove(listener) }
    }

    private fun notifyListeners() {
        val current = current()
        _stateFlow.value = current
        val activeListeners = synchronized(lock) { listeners.toList() }
        activeListeners.forEach { it(current) }
    }
}


// ===== from app/src/main/java/com/blacktunnel/ui/screens/LogViewModel.kt =====
class LogViewModel : ViewModel() {
    val entries = LogSink.entries.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    fun add(icon: String, text: String, level: LogLevel = LogLevel.INFO) {
        LogSink.add(icon, text, level)
    }

    fun clear() {
        LogSink.clear()
    }
}


// ===== from app/src/main/java/com/blacktunnel/ui/screens/MainScreen.kt =====
enum class VpnState { IDLE, CONNECTING, CONNECTED, ERROR }

data class LogEntry(val icon: String, val text: String, val color: Color)

@Composable
fun MainScreen(
    state: VpnState,
    clientName: String,
    daysLeft: String,
    latencyMs: Long,
    status: String,
    connectedSince: Long,
    logEntries: List<LogEntry>,
    onConnectClick: () -> Unit,
    onCopyClientId: () -> Unit,
    isHotspotEnabled: Boolean,
    onHotspotToggle: (Boolean) -> Unit,
    hotspotIp: String?,
    isWifiDirectEnabled: Boolean,
    onWifiDirectToggle: (Boolean) -> Unit,
    wifiDirectPassword: String,
    onWifiDirectPasswordChange: (String) -> Unit,
    onIgnoreBatteryClick: () -> Unit
) {
    var showAdvanced by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (state == VpnState.CONNECTED || state == VpnState.ERROR) {
            Image(
                painter = painterResource(id = R.drawable.bg_connected),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = if (state == VpnState.CONNECTED) 0.06f else 0.045f,
                modifier = Modifier.fillMaxSize()
            )
        }

        val glowColor = when (state) {
            VpnState.CONNECTED -> MaterialTheme.colorScheme.primary.copy(0.16f)
            VpnState.ERROR -> MaterialTheme.colorScheme.error.copy(0.14f)
            VpnState.CONNECTING -> Color(0xFFFBBF24).copy(0.12f)
            else -> Color.Transparent
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(Brush.radialGradient(listOf(glowColor, Color.Transparent)))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Nexora", color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onCopyClientId) {
                    Icon(Icons.Default.CopyAll, contentDescription = "Copiar ID", tint = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(Modifier.height(16.dp))
            Orb(state)
            Spacer(Modifier.height(14.dp))

            val stateLabel = when (state) {
                VpnState.CONNECTED -> "Conectado"
                VpnState.CONNECTING -> "Conectando..."
                VpnState.ERROR -> "Error"
                VpnState.IDLE -> "Desconectado"
            }
            Text(stateLabel, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)

            if (state == VpnState.CONNECTED && connectedSince > 0) {
                Text(formatConnectedSince(connectedSince), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(0.7f))
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                InfoChip("⚡", if (latencyMs >= 0) "${latencyMs}ms" else "-")
                InfoChip("📅", "$daysLeft días")
                InfoChip("👤", clientName.ifBlank { "-" })
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onConnectClick,
                enabled = state != VpnState.CONNECTING,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(if (state == VpnState.CONNECTED) "DESCONECTAR" else "CONECTAR", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(16.dp))
            AdvancedSection(
                expanded = showAdvanced,
                onToggle = { showAdvanced = !showAdvanced },
                isHotspotEnabled = isHotspotEnabled,
                onHotspotToggle = onHotspotToggle,
                hotspotIp = hotspotIp,
                isWifiDirectEnabled = isWifiDirectEnabled,
                onWifiDirectToggle = onWifiDirectToggle,
                wifiDirectPassword = wifiDirectPassword,
                onWifiDirectPasswordChange = onWifiDirectPasswordChange,
                onIgnoreBatteryClick = onIgnoreBatteryClick
            )

            Spacer(Modifier.height(14.dp))
            LogPanel(logEntries = logEntries, fallbackStatus = status)
        }
    }
}

@Composable
private fun Orb(state: VpnState) {
    val tint = when (state) {
        VpnState.CONNECTED -> MaterialTheme.colorScheme.primary
        VpnState.ERROR -> MaterialTheme.colorScheme.error
        VpnState.CONNECTING -> Color(0xFFFBBF24)
        else -> MaterialTheme.colorScheme.onSurface.copy(0.3f)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(170.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(tint.copy(0.25f), Color.Transparent)))
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_nexora_logo),
            contentDescription = null,
            modifier = Modifier.size(92.dp)
        )
    }
}

@Composable
private fun InfoChip(icon: String, label: String) {
    Surface(
        modifier = Modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon)
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
fun LogPanel(logEntries: List<LogEntry>, fallbackStatus: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Log de conexión", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(0.8f), fontWeight = FontWeight.SemiBold)
                Text(if (expanded) "Ocultar" else "Ver todo", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
            val entries = if (logEntries.isNotEmpty()) logEntries.takeLast(6) else listOf(LogEntry("ℹ", fallbackStatus, Color(0xFF94A3B8)))
            val visibleEntries = if (expanded) entries else listOf(entries.last())
            visibleEntries.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(entry.color.copy(alpha = 0.08f))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.width(3.dp).height(18.dp).background(entry.color))
                    Spacer(Modifier.width(8.dp))
                    Text("${entry.icon}  ${entry.text}", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun AdvancedSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    isHotspotEnabled: Boolean,
    onHotspotToggle: (Boolean) -> Unit,
    hotspotIp: String?,
    isWifiDirectEnabled: Boolean,
    onWifiDirectToggle: (Boolean) -> Unit,
    wifiDirectPassword: String,
    onWifiDirectPasswordChange: (String) -> Unit,
    onIgnoreBatteryClick: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = if (expanded) "▲ Ajustes avanzados" else "▼ Ajustes avanzados",
                modifier = Modifier.clickable { onToggle() },
                color = MaterialTheme.colorScheme.onSurface
            )

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Divider()
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("🔥 Compartir Wi‑Fi por proxy")
                        Switch(checked = isHotspotEnabled, onCheckedChange = onHotspotToggle)
                    }
                    if (isHotspotEnabled && hotspotIp != null) {
                        Text("SOCKS5: $hotspotIp:1080", fontSize = 12.sp)
                        Text("HTTP: $hotspotIp:8282", fontSize = 12.sp)
                    }
                    Divider()
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("📺 WiFi Direct para Smart TV")
                        Switch(checked = isWifiDirectEnabled, onCheckedChange = onWifiDirectToggle)
                    }
                    if (isWifiDirectEnabled) {
                        OutlinedTextField(
                            value = wifiDirectPassword,
                            onValueChange = onWifiDirectPasswordChange,
                            label = { Text("Contraseña WiFi") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Divider()
                    Text(
                        text = "🔋 Quitar restricción de batería",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onIgnoreBatteryClick)
                    )
                }
            }
        }
    }
}

private fun formatConnectedSince(connectedSince: Long): String {
    val now = System.currentTimeMillis()
    val mins = ((now - connectedSince) / 60_000L).coerceAtLeast(0)
    val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(connectedSince))
    return "Conectado desde las $hhmm · $mins min"
}


// ===== from app/src/main/java/com/blacktunnel/ui/theme/Theme.kt =====
val NexoraDark = darkColorScheme(
    primary = Color(0xFF00E5FF),
    background = Color(0xFF0A0E1A),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFE2E8F0),
    error = Color(0xFFFF4C6A)
)

@Composable
fun NexoraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NexoraDark,
        content = content
    )
}