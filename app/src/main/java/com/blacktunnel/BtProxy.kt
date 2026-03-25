package com.blacktunnel

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object BtProxy {

    private const val PROXY_IPV6 = "2606:4700::6812:16b7"
    private const val PROXY_HOST = "emailmarketing.personal.com.ar"
    private const val PROXY_PORT = 80
    private const val TUNNEL_HOST = "1.brawlpass.com.ar"
    private const val VLESS_UUID = "11111111-1111-1111-1111-111111111111"

    @Volatile
    private var session: SmuxSession? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    private val running = AtomicBoolean(false)

    fun start(
        hwid: String,
        tunnelDomain: String,
        protectSocket: (java.net.Socket) -> Unit,
        logger: (String) -> Unit
    ) {
        if (running.getAndSet(true)) {
            logger("BtProxy ya estaba corriendo")
            return
        }

        logger("BtProxy init tunnelHost=$TUNNEL_HOST uuid=$VLESS_UUID")

        val tunnelSocket = openTunnel(hwid, tunnelDomain, protectSocket, logger)
        if (tunnelSocket == null) {
            logger("ERROR: no se pudo abrir túnel BT")
            running.set(false)
            return
        }

        session = SmuxSession(tunnelSocket, logger)
        logger("SmuxSession activa sobre túnel BT")

        val srv = ServerSocket(10808, 128, InetAddress.getByName("127.0.0.1"))
        serverSocket = srv
        logger("BtProxy SOCKS5 escuchando en 127.0.0.1:10808")

        thread(isDaemon = true, name = "btproxy-accept") {
            while (running.get()) {
                try {
                    val client = srv.accept()
                    client.tcpNoDelay = true
                    handleSocks5(client, session!!, logger)
                } catch (_: Exception) {
                    if (running.get()) logger("WARN accept falló")
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        runCatching { session?.close() }
        session = null
        serverSocket = null
    }

    private fun openTunnel(
        hwid: String,
        tunnelDomain: String,
        protectSocket: (java.net.Socket) -> Unit,
        logger: (String) -> Unit
    ): java.net.Socket? {
        return try {
            val addr = InetSocketAddress(Inet6Address.getByName(PROXY_IPV6), PROXY_PORT)
            val sock = java.net.Socket()
            protectSocket(sock)
            sock.connect(addr, 10_000)
            sock.soTimeout = 30_000
            sock.tcpNoDelay = true

            val out = sock.getOutputStream()
            val inp = BufferedReader(InputStreamReader(sock.getInputStream()))

            val p1 = "GET / HTTP/1.1\r\nHost: $PROXY_HOST\r\n\r\n"
            out.write(p1.toByteArray())

            val p2 = "- / HTTP/1.1\r\n" +
                "Host: $tunnelDomain\r\n" +
                "Upgrade: websocket\r\n" +
                "Action: tunnel\r\n" +
                "Auth: $hwid\r\n" +
                "Dns-Mode: none\r\n\r\n"
            out.write(p2.toByteArray())
            out.flush()

            val sb = StringBuilder()
            var line: String?
            while (inp.readLine().also { line = it } != null) {
                sb.appendLine(line)
                if (line.isNullOrBlank()) break
            }
            val response = sb.toString()
            if (!response.contains("x-status: OK", ignoreCase = true)) {
                logger("ERROR túnel rechazado: $response")
                sock.close()
                return null
            }
            logger("Túnel BT abierto OK")
            sock
        } catch (e: Exception) {
            logger("ERROR abriendo túnel: ${e.message}")
            null
        }
    }

    private fun handleSocks5(
        client: java.net.Socket,
        smux: SmuxSession,
        logger: (String) -> Unit
    ) {
        thread(isDaemon = true) {
            runCatching {
                val cin = client.getInputStream()
                val cout = client.getOutputStream()

                val ver = cin.read()
                if (ver != 5) {
                    client.close()
                    return@thread
                }
                val nMethods = cin.read()
                repeat(nMethods) { cin.read() }
                cout.write(byteArrayOf(5, 0))
                cout.flush()

                cin.read()
                val cmd = cin.read()
                cin.read()
                val atyp = cin.read()

                val host = when (atyp) {
                    1 -> {
                        val b = ByteArray(4).also { cin.read(it) }
                        "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}." +
                            "${b[2].toInt() and 0xFF}.${b[3].toInt() and 0xFF}"
                    }

                    3 -> {
                        val len = cin.read()
                        String(ByteArray(len).also { cin.read(it) })
                    }

                    4 -> {
                        val b = ByteArray(16).also { cin.read(it) }
                        b.joinToString(":") { "%02x".format(it) }
                    }

                    else -> {
                        client.close()
                        return@thread
                    }
                }
                val port = ((cin.read() and 0xFF) shl 8) or (cin.read() and 0xFF)

                if (cmd == 3) {
                    cout.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    cout.flush()
                    Thread.sleep(300_000)
                    client.close()
                    return@thread
                }

                if (cmd != 1) {
                    client.close()
                    return@thread
                }

                logger("CONNECT $host:$port")

                val stream = smux.openStream()
                val dest = "$host:$port\n".toByteArray()
                stream.write(dest)

                cout.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                cout.flush()

                val streamIn = stream.inputStream()
                val streamOut = stream.outputStream()
                val buf = ByteArray(32768)

                val uplink = thread(isDaemon = true) {
                    runCatching {
                        while (true) {
                            val n = cin.read(buf)
                            if (n < 0) break
                            streamOut.write(buf, 0, n)
                            streamOut.flush()
                        }
                    }
                    runCatching { stream.close() }
                }

                runCatching {
                    while (true) {
                        val n = streamIn.read(buf)
                        if (n < 0) break
                        cout.write(buf, 0, n)
                        cout.flush()
                    }
                }

                uplink.join(1000)
                runCatching { client.close() }
            }.onFailure {
                runCatching { client.close() }
            }
        }
    }
}
