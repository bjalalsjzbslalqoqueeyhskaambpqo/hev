package com.blacktunnel

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

object BtProxy {

    private const val PROXY_IPV6  = "2606:4700::6812:16b7"
    private const val PROXY_HOST  = "emailmarketing.personal.com.ar"
    private const val PROXY_PORT  = 80
    private const val TUNNEL_HOST = "7.brawlpass.com.ar"

    private const val XRAY_SOCKS5_PORT = 10808
    private const val TUNNEL_LOCAL_PORT = 10809
    private const val MAX_PARALLEL_TUNNELS = 8
    private const val TEST_UUID = "a3482e88-686a-4a58-8126-99c9df64b7bf"

    @Volatile private var xrayProcess: Process? = null
    @Volatile private var running = false
    @Volatile private var muxConcurrency: Int = 8
    private val tunnelSlots = Semaphore(MAX_PARALLEL_TUNNELS)

    fun start(
        ctx: Context,
        mux: Int,
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ) {
        running = true
        muxConcurrency = mux.coerceIn(1, 64)
        logger("BtProxy.start()")
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
                    tout.flush()
                }
            }
        }
        val down = thread(isDaemon = true) {
            runCatching {
                val tin = tunnel.getInputStream()
                val cout = client.getOutputStream()
                while (true) {
                    val n = tin.read(buf)
                    if (n < 0) break
                    cout.write(buf, 0, n)
                    cout.flush()
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
            config.writeText(buildClientConfig())

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

    private fun buildClientConfig(): String {
        return """
            {
              "log": { "loglevel": "warning" },
              "inbounds": [
                {
                  "protocol": "socks",
                  "listen": "127.0.0.1",
                  "port": $XRAY_SOCKS5_PORT,
                  "settings": { "udp": true }
                }
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
                    "xudpConcurrency": $muxConcurrency,
                    "xudpProxyUDP443": "allow"
                  }
                }
              ]
            }
        """.trimIndent()
    }

    private fun openTunnel(
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ): Socket? {
        return try {
            val sock = Socket()
            protectSocket(sock)
            sock.connect(InetSocketAddress(Inet6Address.getByName(PROXY_IPV6), PROXY_PORT), 10_000)
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
            if (handshake == null || handshake.statusCode != 101) {
                logger("ERROR túnel rechazado code=${handshake?.statusCode ?: -1}")
                TunnelSessionStore.setState("ERROR")
                sock.close()
                return null
            }

            val headersForUi = handshake.headers.toMutableMap()
            if (headersForUi["x-status"].isNullOrBlank()) {
                headersForUi["x-status"] = "OK"
            }
            logger("Handshake seleccionado code=${handshake.statusCode} x-status=${headersForUi["x-status"]}")
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
            TunnelSessionStore.setState("CONNECTED")
            logger("Túnel OK via IPv6 $PROXY_IPV6")
            sock
        } catch (e: Exception) {
            logger("ERROR abriendo túnel: ${e.message}")
            TunnelSessionStore.setState("ERROR")
            null
        }
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

}