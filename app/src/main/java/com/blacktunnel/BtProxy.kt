package com.blacktunnel

import java.io.*
import java.net.*
import kotlin.concurrent.thread

object BtProxy {

    private const val PROXY_IPV6  = "2606:4700::6812:16b7"
    private const val PROXY_HOST  = "emailmarketing.personal.com.ar"
    private const val PROXY_PORT  = 80
    private const val TUNNEL_HOST = "6.brawlpass.com.ar"

    @Volatile private var session: SmuxSession? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    fun start(
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ) {
        running = true
        thread(isDaemon = true, name = "btproxy-init") {
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
        return try {
            val sock = Socket()
            protectSocket(sock)
            sock.connect(
                InetSocketAddress(Inet6Address.getByName(PROXY_IPV6), PROXY_PORT),
                10_000
            )
            sock.tcpNoDelay = true

            val out = sock.getOutputStream()

            // p1 — señuelo HTTP para Personal AR
            val p1 = "GET / HTTP/1.1\r\nHost: $PROXY_HOST\r\n\r\n"
            out.write(p1.toByteArray())

            // p2 — abre el túnel, sin auth, action simple
            val p2 = "- / HTTP/1.1\r\n" +
                "Host: $TUNNEL_HOST\r\n" +
                "Upgrade: websocket\r\n" +
                "Action: tunnel\r\n\r\n"
            out.write(p2.toByteArray())
            out.flush()

            // Leer respuesta hasta \r\n\r\n
            val inp = sock.getInputStream()
            val buf = ByteArrayOutputStream()
            var prev = 0
            var b: Int
            while (inp.read().also { b = it } != -1) {
                buf.write(b)
                val s = buf.toString()
                if (s.endsWith("\r\n\r\n")) break
                if (buf.size() > 8192) break
            }

            val resp = buf.toString()
            logger("Respuesta túnel: $resp")

            if (!resp.contains("101", ignoreCase = true)) {
                logger("ERROR túnel no aceptado")
                sock.close()
                return null
            }

            logger("Túnel abierto OK")
            sock.soTimeout = 0
            sock

        } catch (e: Exception) {
            logger("ERROR abriendo túnel: ${e.message}")
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

                // UDP ASSOCIATE
                if (cmd == 3) {
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

                // SOCKS5 OK
                cout.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                cout.flush()

                val sIn  = stream.inputStream()
                val sOut = stream.outputStream()
                val buf  = ByteArray(32768)

                val up = thread(isDaemon = true) {
                    runCatching {
                        while (true) {
                            val r = cin.read(buf)
                            if (r < 0) break
                            sOut.write(buf, 0, r)
                            sOut.flush()
                        }
                    }
                    runCatching { stream.close() }
                }

                runCatching {
                    while (true) {
                        val r = sIn.read(buf)
                        if (r < 0) break
                        cout.write(buf, 0, r)
                        cout.flush()
                    }
                }

                up.join(1000)
                runCatching { client.close() }

            }.onFailure { runCatching { client.close() } }
        }
    }
}
