package com.blacktunnel

import java.io.*
import java.net.*
import kotlin.concurrent.thread

object BtProxy {

    private const val PROXY_IPV4  = "190.210.0.136"
    private const val PROXY_IPV6  = "2606:4700::6812:16b7"
    private const val PROXY_HOST  = "emailmarketing.personal.com.ar"
    private const val PROXY_PORT  = 80
    private const val TUNNEL_HOST = "7.brawlpass.com.ar"

    @Volatile private var session: SmuxSession? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    fun start(
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ) {
        running = true
        logger("BtProxy.start() running=true")
        thread(isDaemon = true, name = "btproxy-init") {
            logger("BtProxy init: abriendo túnel")
            val tunnelSock = openTunnel(protectSocket, logger) ?: run {
                logger("ERROR no se pudo abrir túnel")
                return@thread
            }
            session = SmuxSession(tunnelSock, logger)
            logger("SmuxSession activa")
            startSocks5Server(logger)
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        runCatching { session?.close() }
        session = null
        serverSocket = null
    }

    private fun openTunnel(
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ): Socket? {
        val candidates = listOf(
            Pair(false, PROXY_IPV4),
            Pair(true, PROXY_IPV6)
        )
        for ((isV6, ip) in candidates) {
            logger("Intentando ${if (isV6) "IPv6" else "IPv4"} $ip...")
            val sock = tryOpenTunnel(ip, isV6, protectSocket, logger)
            if (sock != null) return sock
        }
        return null
    }

    private fun tryOpenTunnel(
        ip: String,
        isV6: Boolean,
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ): Socket? {
        return try {
            val family = if (isV6) Inet6Address.getByName(ip)
            else Inet4Address.getByName(ip)
            val sock = Socket()
            protectSocket(sock)
            sock.connect(InetSocketAddress(family, PROXY_PORT), 8_000)
            sock.tcpNoDelay = true

            val out = sock.getOutputStream()
            val inp = sock.getInputStream()

            val p1 = "GET / HTTP/1.1\r\nHost: $PROXY_HOST\r\n\r\n"
            out.write(p1.toByteArray())
            out.flush()
            logger("TX p1 host=$PROXY_HOST")

            sock.soTimeout = 5000
            val buf1 = ByteArrayOutputStream()
            while (true) {
                val b = inp.read()
                if (b == -1) break
                buf1.write(b)
                if (buf1.toString().endsWith("\r\n\r\n")) break
                if (buf1.size() > 8192) break
            }
            logger("RX p1: ${buf1.toString().lines().firstOrNull() ?: "<vacío>"}")

            val p2 = "- / HTTP/1.1\r\n" +
                "Host: $TUNNEL_HOST\r\n" +
                "Upgrade: websocket\r\n" +
                "Action: tunnel\r\n\r\n"
            out.write(p2.toByteArray())
            out.flush()
            logger("TX p2 host=$TUNNEL_HOST action=tunnel")

            sock.soTimeout = 8000
            val buf2 = ByteArrayOutputStream()
            while (true) {
                val b = inp.read()
                if (b == -1) break
                buf2.write(b)
                if (buf2.toString().endsWith("\r\n\r\n")) break
                if (buf2.size() > 8192) break
            }
            val resp = buf2.toString()
            logger("RX p2: $resp")

            if (!resp.contains("X-Status: OK", ignoreCase = true)) {
                logger("WARN túnel rechazado por $ip")
                sock.close()
                return null
            }

            logger("Túnel OK via ${if (isV6) "IPv6" else "IPv4"} $ip")
            sock.soTimeout = 0
            sock
        } catch (e: Exception) {
            logger("WARN $ip falló: ${e.message}")
            null
        }
    }

    private fun startSocks5Server(logger: (String) -> Unit) {
        val srv = ServerSocket(10808, 128, InetAddress.getByName("127.0.0.1"))
        serverSocket = srv
        logger("BtProxy escuchando SOCKS5 en 127.0.0.1:10808")

        while (running) {
            try {
                val client = srv.accept()
                client.tcpNoDelay = true
                val smux = session
                if (smux == null || !smux.isOpen) {
                    logger("WARN smux no disponible, cerrando cliente")
                    client.close()
                    continue
                }
                handleSocks5(client, smux, logger)
            } catch (_: Exception) {
                if (running) logger("WARN accept error")
            }
        }
    }

    private fun handleSocks5(
        client: Socket,
        smux: SmuxSession,
        logger: (String) -> Unit
    ) {
        thread(isDaemon = true) {
            runCatching {
                val cin  = client.getInputStream()
                val cout = client.getOutputStream()

                // Handshake SOCKS5
                if (cin.read() != 5) { client.close(); return@thread }
                val n = cin.read()
                repeat(n) { cin.read() }
                cout.write(byteArrayOf(5, 0))
                cout.flush()

                // Request
                cin.read() // ver
                val cmd  = cin.read()
                cin.read() // rsv
                val atyp = cin.read()
                logger("SOCKS req cmd=$cmd atyp=$atyp")

                val host = when (atyp) {
                    1 -> {
                        val b = ByteArray(4).also { cin.read(it) }
                        "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}" +
                        ".${b[2].toInt() and 0xFF}.${b[3].toInt() and 0xFF}"
                    }
                    3 -> String(ByteArray(cin.read()).also { cin.read(it) })
                    4 -> {
                        val b = ByteArray(16).also { cin.read(it) }
                        b.joinToString(":") { "%02x".format(it) }
                    }
                    else -> { client.close(); return@thread }
                }
                val port = (cin.read() shl 8) or cin.read()
                logger("SOCKS destino $host:$port")

                // UDP ASSOCIATE
                if (cmd == 3) {
                    logger("SOCKS UDP_ASSOCIATE -> responder OK y mantener vivo")
                    cout.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    cout.flush()
                    Thread.sleep(300_000)
                    client.close()
                    return@thread
                }

                if (cmd != 1) { client.close(); return@thread }

                logger("→ $host:$port")

                // Abrir stream smux — primer payload es "host:port\n"
                val stream = smux.openStream()
                stream.write("$host:$port\n".toByteArray())
                logger("smux stream abierto y destino enviado")

                // SOCKS5 OK
                cout.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                cout.flush()

                val sIn  = stream.inputStream()
                val sOut = stream.outputStream()
                val buf  = ByteArray(32768)

                val up = thread(isDaemon = true) {
                    var sent = 0L
                    runCatching {
                        while (true) {
                            val r = cin.read(buf)
                            if (r < 0) break
                            sOut.write(buf, 0, r)
                            sOut.flush()
                            sent += r
                        }
                    }
                    logger("uplink fin bytes=$sent")
                    runCatching { stream.close() }
                }

                var recv = 0L
                runCatching {
                    while (true) {
                        val r = sIn.read(buf)
                        if (r < 0) break
                        cout.write(buf, 0, r)
                        cout.flush()
                        recv += r
                    }
                }
                logger("downlink fin bytes=$recv")

                up.join(1000)
                runCatching { client.close() }
                logger("cliente SOCKS cerrado")

            }.onFailure {
                logger("ERROR handleSocks5: ${it.message}")
                runCatching { client.close() }
            }
        }
    }

}
