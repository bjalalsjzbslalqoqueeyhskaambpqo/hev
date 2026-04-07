package com.blacktunnel

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.VpnService
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
            if (desiredRunning && !isStopping) scheduleRestart(applicationContext, 3000)
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
        if (desiredRunning) runCatching { scheduleRestart(applicationContext, 1000) }
    }

    override fun onDestroy() {
        runCatching { if (desiredRunning) scheduleRestart(applicationContext, 2000) }
        runCatching { stopTunnel() }
        runCatching { super.onDestroy() }
    }

    private fun scheduleRestart(ctx: Context, delayMs: Long) {
        val pending = PendingIntent.getService(
            ctx, 99, startIntent(ctx),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        (ctx.getSystemService(ALARM_SERVICE) as AlarmManager)
            .setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pending)
    }

    private fun startTunnel() {
        runCatching {
            isStopping = false
            if (pfd != null) return
            TunnelSessionStore.setState("CONNECTING")
            startVpnForeground()

            thread(isDaemon = true, name = "vpn-start-sequence") {
                runCatching {
                    val clientId = TunnelPrefs.getOrCreateClientId(this@BtVpnService)

                    BtProxy.onFatalAuthError = {
                        runCatching { stopTunnel() }
                    }

                    BtProxy.onTunnelDied = {
                        runCatching { tearDownTunLayer() }
                    }

                    BtProxy.onTunnelReady = {
                        thread(isDaemon = true, name = "vpn-establish") {
                            runCatching {
                                val builder = Builder()
                                    .setSession("XTunnel")
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
                                    runCatching { BtProxy.stop() }
                                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                                    runCatching { stopSelf() }
                                    return@runCatching
                                }

                                pfd = established
                                runCatching { registerNetworkReceiver() }

                                val rawFd = runCatching {
                                    ParcelFileDescriptor.dup(established.fileDescriptor).detachFd()
                                }.getOrNull() ?: run {
                                    TunnelSessionStore.setState("ERROR")
                                    return@runCatching
                                }

                                rawTunFd = rawFd
                                val configFile = runCatching { writeHevConfig() }.getOrNull() ?: return@runCatching

                                thread(isDaemon = true, name = "hev-main") {
                                    runCatching { HevBridge.start(configFile.absolutePath, rawFd) }
                                    runCatching { closeRawTunFd() }
                                    if (desiredRunning && !isStopping) {
                                        android.util.Log.w("BTCRASH", "hev-main terminó inesperadamente, señalando muerte de túnel")
                                        BtProxy.signalTunLayerDied()
                                    }
                                }
                            }.onFailure { e ->
                                android.util.Log.e("BTCRASH", "vpn-establish crash: ${e.message}")
                                TunnelSessionStore.setState("ERROR")
                            }
                        }
                    }

                    BtProxy.start(
                        ctx = this@BtVpnService,
                        clientId = clientId,
                        protectSocket = { socket -> runCatching { protect(socket) } }
                    )
                }.onFailure { e ->
                    android.util.Log.e("BTCRASH", "vpn-start-sequence crash: ${e.message}")
                    TunnelSessionStore.setState("ERROR")
                }
            }
        }.onFailure { e ->
            android.util.Log.e("BTCRASH", "startTunnel crash: ${e.message}")
        }
    }

    internal fun tearDownTunLayer() {
        runCatching { HevBridge.stop() }
        runCatching { closeRawTunFd() }
        runCatching { pfd?.close() }
        pfd = null
        android.util.Log.i("BTCRASH", "TUN layer bajado, esperando que BtProxy reconecte y levante de nuevo")
    }

    private fun startVpnForeground() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(VPN_CHANNEL_ID, "XTunnel VPN", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Servicio VPN activo"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
            val openAppIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val disconnectIntent = PendingIntent.getService(this, 0, stopIntent(this), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, VPN_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("XTunnel activo")
                .setContentText("Conexión protegida")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openAppIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Desconectar", disconnectIntent)
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
                .map { it.packageName }.filter { it != packageName }
                .forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
            return
        }
        includedApps.forEach { pkg -> runCatching { builder.addAllowedApplication(pkg) } }
    }

    private fun writeHevConfig(): java.io.File {
        val file = java.io.File(filesDir, "hev.yml")
        file.writeText("""
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
            """.trimIndent())
        return file
    }

    companion object {
        private const val ACTION_START = "com.blacktunnel.START"
        private const val ACTION_STOP = "com.blacktunnel.STOP"
        private const val VPN_CHANNEL_ID = "vpn_channel"
        private const val VPN_NOTIFICATION_ID = 1

        fun startIntent(context: Context): Intent =
            Intent(context, BtVpnService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, BtVpnService::class.java).setAction(ACTION_STOP)

        fun resolveHotspotIp(): String? = BtProxy.getHotspotIp()
    }
}


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

    private const val RECONNECT_BASE_MS = 3000L
    private const val RECONNECT_MAX_MS = 30000L
    private const val NETWORK_WAIT_TIMEOUT_MS = 120_000L
    private const val KEEPALIVE_INTERVAL_MS = 20_000L
    private const val KEEPALIVE_TIMEOUT_MS = 15_000L
    private const val SOCKET_CONNECT_TIMEOUT_MS = 10_000
    private const val HANDSHAKE_TIMEOUT_MS = 8000L

    private const val DNS_TTL_MS = 30 * 60 * 1000L
    @Volatile private var cachedAddresses: List<InetAddress> = emptyList()
    @Volatile private var cacheTimestamp = 0L

    private fun resolveProxyAddresses(): List<InetAddress> {
        val now = System.currentTimeMillis()
        if (cachedAddresses.isNotEmpty() && now - cacheTimestamp < DNS_TTL_MS) return cachedAddresses
        val fresh = linkedSetOf<InetAddress>()
        runCatching { fresh += InetAddress.getByName(PROXY_IPV6) }
        runCatching { fresh += InetAddress.getAllByName(PROXY_HOST).toList() }
        if (fresh.isNotEmpty()) { cachedAddresses = fresh.toList(); cacheTimestamp = now }
        return if (cachedAddresses.isNotEmpty()) cachedAddresses else fresh.toList()
    }

    fun clearDnsCache() { cachedAddresses = emptyList(); cacheTimestamp = 0L }

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
    @Volatile var onFatalAuthError: (() -> Unit)? = null
    @Volatile var onTunnelReady: (() -> Unit)? = null
    @Volatile var onTunnelDied: (() -> Unit)? = null

    private val nextStreamId = AtomicInteger(1)
    private val streams = ConcurrentHashMap<Int, Socket>()
    private val tunnelLock = Any()
    private val reconnectLock = Any()
    @Volatile private var reconnectScheduled = false

    fun signalTunLayerDied() {
        if (!running || authFatalError) return
        onTunnelDied?.invoke()
        scheduleReconnect(fromTunDeath = true)
    }

    fun start(ctx: Context, clientId: String, protectSocket: (Socket) -> Unit) {
        currentClientId = clientId.trim()
        savedCtx = ctx
        savedProtect = protectSocket
        running = true
        reconnectAttempts = 0
        authFatalError = false
        reconnectScheduled = false
        thread(isDaemon = true, name = "btproxy-init") { connectTunnel(ctx, protectSocket) }
    }

    fun stop() {
        running = false
        savedCtx = null
        savedProtect = null
        reconnectAttempts = 0
        authFatalError = false
        reconnectScheduled = false
        onTunnelReady = null
        onFatalAuthError = null
        onTunnelDied = null
        runCatching { bridgeServer?.close() }
        bridgeServer = null
        streams.values.forEach { runCatching { it.close() } }
        streams.clear()
        runCatching { tunnelSocket?.close() }
        tunnelSocket = null
        tunnelOut = null
        xrayProcess?.let { it.destroy(); if (it.isAlive) it.destroyForcibly() }
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

        onTunnelReady?.invoke()
        TunnelSessionStore.setState("CONNECTED")
        LogSink.add("🔒", "Conectado · túnel establecido", LogLevel.SUCCESS)
    }

    private fun startKeepalive() {
        thread(isDaemon = true, name = "tunnel-keepalive") {
            while (running && tunnelSocket?.isConnected == true && !tunnelSocket!!.isClosed) {
                Thread.sleep(KEEPALIVE_INTERVAL_MS)
                if (!running) break
                val out = synchronized(tunnelLock) { tunnelOut } ?: break
                val currentSocket = tunnelSocket ?: break
                if (currentSocket.isClosed || !currentSocket.isConnected) break

                val socketBeforePing = tunnelSocket
                val sent = runCatching {
                    out.writeByte(TYPE_DATA.toInt())
                    out.writeInt(0)
                    out.writeInt(0)
                    out.flush()
                    true
                }.getOrElse { false }

                if (!sent) {
                    android.util.Log.w("BTCRASH", "Keepalive falló, túnel caído")
                    if (running && tunnelSocket === socketBeforePing) {
                        runCatching { tunnelSocket?.close() }
                    }
                    break
                }
            }
        }
    }

    private fun scheduleReconnect(fromTunDeath: Boolean = false) {
        synchronized(reconnectLock) {
            if (!running || authFatalError || reconnectScheduled) return
            reconnectScheduled = true
        }
        reconnectAttempts++
        val delay = (reconnectAttempts * RECONNECT_BASE_MS).coerceAtMost(RECONNECT_MAX_MS)
        LogSink.add("⟳", "Reconectando (intento $reconnectAttempts) en ${delay/1000}s...", LogLevel.WARN)
        TunnelSessionStore.setState("CONNECTING")

        thread(isDaemon = true, name = "btproxy-reconnect-$reconnectAttempts") {
            runCatching {
                Thread.sleep(delay)
                if (!running) return@thread

                synchronized(reconnectLock) { reconnectScheduled = false }

                runCatching { tunnelSocket?.close() }
                tunnelSocket = null
                tunnelOut = null

                val ctx = savedCtx ?: return@thread
                val protect = savedProtect ?: return@thread

                waitForNetwork(ctx)
                if (!running) return@thread

                connectTunnel(ctx, protect)
            }.onFailure { e ->
                android.util.Log.e("BTCRASH", "btproxy-reconnect crash: ${e.message}")
                synchronized(reconnectLock) { reconnectScheduled = false }
                if (running && !authFatalError) scheduleReconnect()
            }
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
                    if (length < 0 || length > 65536) {
                        android.util.Log.w("BTCRASH", "Frame inválido length=$length, cerrando túnel")
                        break
                    }
                    val data = if (length > 0) { val buf = ByteArray(length); inp.readFully(buf); buf } else ByteArray(0)
                    when (type) {
                        TYPE_DATA -> {
                            val client = streams[streamId] ?: continue
                            runCatching { client.getOutputStream().apply { write(data); flush() } }
                        }
                        TYPE_CLOSE -> { val client = streams.remove(streamId); runCatching { client?.close() } }
                    }
                }
            } catch (_: Exception) {
                android.util.Log.w("BTCRASH", "tunnel-reader terminó, programando reconexión")
            } finally {
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
            val config = File(ctx.filesDir, "xray-client.json").also { it.writeText(buildClientConfig(ctx)) }
            xrayProcess = ProcessBuilder(listOf(binary.absolutePath, "run", "-c", config.absolutePath))
                .directory(binary.parentFile ?: File(ctx.applicationInfo.nativeLibraryDir))
                .redirectErrorStream(true)
                .start()
            thread(isDaemon = true, name = "xray-stdout") {
                runCatching { xrayProcess?.inputStream?.copyTo(java.io.OutputStream.nullOutputStream()) }
                if (running) {
                    android.util.Log.w("BTCRASH", "Xray terminó inesperadamente")
                    signalTunLayerDied()
                }
            }
        }
    }

    private fun resolveXrayBinary(ctx: Context): File? {
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        return listOf(
            File(nativeDir, "libxray.so"), File(nativeDir, "xray"),
            File(ctx.filesDir, "libxray.so"), File(ctx.filesDir, "xray")
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
              "0": { "handshake": 4, "connIdle": 600, "uplinkOnly": 5, "downlinkOnly": 10, "bufferSize": 512 }
            },
            "system": { "udpTimeout": 0, "connIdle": 600, "downlinkOnly": 30, "uplinkOnly": 30 }
          },
          "inbounds": [
            {
              "protocol": "socks", "listen": "127.0.0.1", "port": $XRAY_SOCKS5_PORT,
              "settings": { "udp": true },
              "sniffing": { "enabled": true, "destOverride": ["http", "tls", "quic", "fakedns"], "metadataOnly": false }
            }${buildHotspotInbound(ctx)}
          ],
          "outbounds": [{
            "protocol": "vless",
            "settings": { "vnext": [{ "address": "127.0.0.1", "port": $TUNNEL_LOCAL_PORT,
              "users": [{ "id": "$TEST_UUID", "encryption": "none" }] }] },
            "streamSettings": { "network": "tcp", "security": "none" },
            "mux": { "enabled": true, "concurrency": 128, "xudpConcurrency": 1024, "xudpProxyUDP443": "allow" },
            "targetStrategy": "UseIPv4"
          }]
        }
    """.trimIndent()

    private fun buildHotspotInbound(ctx: Context): String {
        if (!TunnelPrefs.isHotspotProxyEnabled(ctx)) return ""
        val ip = getHotspotIp() ?: return ""
        return """,
            {
              "protocol": "socks", "listen": "0.0.0.0", "port": 1080,
              "settings": { "udp": true, "ip": "$ip" },
              "sniffing": { "enabled": true, "destOverride": ["http", "tls", "quic", "fakedns"], "metadataOnly": false }
            },
            {
              "protocol": "http", "listen": "0.0.0.0", "port": 8282,
              "settings": {},
              "sniffing": { "enabled": true, "destOverride": ["http", "tls", "fakedns"], "metadataOnly": false }
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
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager

        if (isNetworkValidated(cm)) return

        LogSink.add("📡", "Sin red · esperando señal...", LogLevel.WARN)
        TunnelSessionStore.setState("CONNECTING")

        val deadline = System.currentTimeMillis() + NETWORK_WAIT_TIMEOUT_MS

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val latch = java.util.concurrent.CountDownLatch(1)
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {}
                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    caps: android.net.NetworkCapabilities
                ) {
                    if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        latch.countDown()
                    }
                }
                override fun onLost(network: android.net.Network) {}
            }
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching { cm.registerNetworkCallback(request, callback) }
            try {
                while (running && latch.count > 0 && System.currentTimeMillis() < deadline) {
                    latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                    if (latch.count > 0 && isNetworkValidated(cm)) latch.countDown()
                }
                if (latch.count > 0 && !running) {
                    android.util.Log.w("BTCRASH", "waitForNetwork: detenido por flag running=false")
                } else if (latch.count > 0) {
                    android.util.Log.w("BTCRASH", "waitForNetwork: timeout de ${NETWORK_WAIT_TIMEOUT_MS/1000}s, continuando igual")
                }
            } finally {
                runCatching { cm.unregisterNetworkCallback(callback) }
            }
        } else {
            while (running && !isNetworkValidated(cm) && System.currentTimeMillis() < deadline) {
                Thread.sleep(2000)
            }
        }

        if (running) Thread.sleep(800)
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

            socket.soTimeout = HANDSHAKE_TIMEOUT_MS.toInt()
            val raw = StringBuilder()
            val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
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
                        onFatalAuthError?.invoke()
                    }
                    status.equals("UNKNOWN", ignoreCase = true) || status.equals("INVALID", ignoreCase = true) -> {
                        LogSink.add("✗", "Usuario no registrado", LogLevel.ERROR)
                        authFatalError = true
                        TunnelSessionStore.setState("ERROR")
                        onFatalAuthError?.invoke()
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
                val message = if (authState.contains("EXPIRED", ignoreCase = true)) "Usuario expirado" else "Usuario inválido"
                LogSink.add("✗", message, LogLevel.ERROR)
                onFatalAuthError?.invoke()
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
            LogSink.add("🔐", "Túnel autenticado · ${latencyMs}ms · Total ${totalMs}ms", LogLevel.OK)
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
                    connect(InetSocketAddress(address, PROXY_PORT), SOCKET_CONNECT_TIMEOUT_MS)
                }
            }.getOrNull()
            if (socket != null) return socket
        }
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


object HevBridge {
    init { System.loadLibrary("hev-jni") }
    external fun start(configPath: String, tunFd: Int): Int
    external fun stop()
}


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
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_INCLUDED_APPS, "").orEmpty()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
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


object LogSink {
    private val _entries = MutableStateFlow<List<com.blacktunnel.ui.screens.LogEntry>>(emptyList())
    val entries = _entries.asStateFlow()

    fun clear() { _entries.value = emptyList() }

    fun add(icon: String, text: String, level: LogLevel = LogLevel.INFO) {
        val entry = com.blacktunnel.ui.screens.LogEntry(icon = icon, text = text, color = level.color)
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


object BtWifiDirect {

    private const val GATEWAY_IP = "192.168.49.1"
    private const val SOCKS5_PORT = 1080
    private const val HTTP_PORT = 8282

    @Volatile var isActive = false
        private set

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    fun getSavedPassword(ctx: Context): String =
        ctx.getSharedPreferences("bt_prefs", Context.MODE_PRIVATE).getString("wifidirect_pass", "12345678") ?: "12345678"

    fun savePassword(ctx: Context, password: String) {
        ctx.getSharedPreferences("bt_prefs", Context.MODE_PRIVATE).edit().putString("wifidirect_pass", password).apply()
    }

    fun start(ctx: Context, onResult: (success: Boolean) -> Unit) {
        val appCtx = ctx.applicationContext
        manager = appCtx.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager?.initialize(appCtx, appCtx.mainLooper, null)
        val activeChannel = channel ?: run { isActive = false; onResult(false); return }

        receiver?.let { runCatching { appCtx.unregisterReceiver(it) } }
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
                .setNetworkName("DIRECT-XTunnel")
                .setPassphrase(getSavedPassword(appCtx))
                .build()
            manager?.createGroup(activeChannel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { isActive = true; onResult(true) }
                override fun onFailure(reason: Int) { isActive = false; onResult(false) }
            })
        } else {
            manager?.createGroup(activeChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { isActive = true; onResult(true) }
                override fun onFailure(reason: Int) { isActive = false; onResult(false) }
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
        "ssid" to "DIRECT-XTunnel",
        "password" to getSavedPassword(ctx),
        "ip" to GATEWAY_IP,
        "socks5" to SOCKS5_PORT,
        "http" to HTTP_PORT
    )
}


class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!TunnelPrefs.wasConnected(context)) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val isConnected = cm.activeNetworkInfo?.isConnected == true
        if (!isConnected) return
        val vpnIntent = BtVpnService.startIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(vpnIntent)
        else context.startService(vpnIntent)
    }
}


class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.ACTION_BOOT_COMPLETED"
        )
        if (intent.action !in validActions) return
        if (!TunnelPrefs.wasConnected(context)) return
        val vpnIntent = BtVpnService.startIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(vpnIntent)
        else context.startService(vpnIntent)
    }
}
