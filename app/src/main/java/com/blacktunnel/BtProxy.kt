package com.blacktunnel

import java.io.*
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object BtProxy {

    private const val PROXY_IPV6  = "2606:4700::6812:16b7"
    private const val PROXY_HOST  = "emailmarketing.personal.com.ar"
    private const val PROXY_PORT  = 80
    private const val TUNNEL_HOST = "7.brawlpass.com.ar"

    @Volatile private var session: SmuxSession? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var protectFn: ((Socket) -> Unit)? = null
    @Volatile private var reconnecting = false
    @Volatile private var running = false
    @Volatile private var lastNoSmuxLogAt = 0L
    @Volatile private var lastUdpLogAt = 0L
    private val ioExecutor = Executors.newFixedThreadPool(16)

    private fun classifyPriority(port: Int): Int {
        return when (port) {
            53, 123, 443, 3478, 3479, 19302, 19305 -> 0 // DNS/QUIC/interactive real-time
            in 1..1024 -> 1024 // control plane
            else -> 20000 // likely bulk/background
        }
    }

    fun start(
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ) {
        running = true
        protectFn = protectSocket
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
        protectFn = null
        session = null
        serverSocket = null
    }

    private fun openTunnel(
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ): Socket? {
        logger("Intentando IPv6 $PROXY_IPV6...")
        return tryOpenTunnel(PROXY_IPV6, true, protectSocket, logger)
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
                    val now = System.currentTimeMillis()
                    if (now - lastNoSmuxLogAt > 2000) {
                        lastNoSmuxLogAt = now
                        logger("WARN smux no disponible, cerrando cliente")
                    }
                    client.close()
                    if (!reconnecting && (session == null || !session!!.isOpen)) {
                        reconnecting = true
                        thread(isDaemon = true, name = "btproxy-reconnect") {
                            try {
                                logger("Reconectando túnel...")
                                val protect = protectFn
                                if (protect == null) {
                                    logger("WARN no hay protectFn, no se puede reconectar")
                                } else {
                                    val sock = openTunnel(protect, logger)
                                    if (sock != null) {
                                        session = SmuxSession(sock, logger)
                                        logger("Reconexión OK")
                                    } else {
                                        logger("WARN reconexión fallida")
                                    }
                                }
                            } finally {
                                reconnecting = false
                            }
                        }
                    }
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
        ioExecutor.execute {
            runCatching {
                val cin  = client.getInputStream()
                val cout = client.getOutputStream()

                // Handshake SOCKS5
                if (cin.read() != 5) { client.close(); return@runCatching }
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
                        val b = readExact(cin, 4) ?: return@runCatching
                        "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}" +
                        ".${b[2].toInt() and 0xFF}.${b[3].toInt() and 0xFF}"
                    }
                    3 -> {
                        val l = cin.read()
                        if (l <= 0) return@runCatching
                        String(readExact(cin, l) ?: return@runCatching)
                    }
                    4 -> {
                        val b = readExact(cin, 16) ?: return@runCatching
                        b.joinToString(":") { "%02x".format(it) }
                    }
                    else -> { client.close(); return@runCatching }
                }
                val port = (cin.read() shl 8) or cin.read()
                // UDP ASSOCIATE
                if (cmd == 3) {
                    handleUdpAssociate(client, cin, cout, smux, logger)
                    return@runCatching
                }

                if (cmd != 1) { client.close(); return@runCatching }

                logger("CONNECT $host:$port")

                // Abrir stream smux — primer payload es "host:port\n"
                val stream = smux.openStream(priorityHint = classifyPriority(port))
                stream.write("$host:$port\n".toByteArray())
                // SOCKS5 OK
                cout.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                cout.flush()

                val sIn  = stream.inputStream()
                val sOut = stream.outputStream()
                val buf  = ByteArray(32768)

                val up = thread(isDaemon = true, name = "btproxy-uplink") {
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
                up.join(1000)
                runCatching { client.close() }

            }.onFailure {
                logger("ERROR handleSocks5: ${it.message}")
                runCatching { client.close() }
            }
        }
    }

    private fun handleUdpAssociate(
        client: Socket,
        cin: InputStream,
        cout: OutputStream,
        smux: SmuxSession,
        logger: (String) -> Unit
    ) {
        val relay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        relay.soTimeout = 1000
        val keepRunning = AtomicBoolean(true)

        val bind = relay.localAddress.address
        val bindPort = relay.localPort
        cout.write(
            byteArrayOf(
                5, 0, 0, 1,
                bind[0], bind[1], bind[2], bind[3],
                ((bindPort ushr 8) and 0xFF).toByte(),
                (bindPort and 0xFF).toByte()
            )
        )
        cout.flush()

        val stream = smux.openStream(priorityHint = 0)
        stream.write("UDP\n".toByteArray())
        val senderRef = arrayOfNulls<SocketAddress>(1)
        val upBuf = ByteArray(65535)
        val localToSmux = thread(isDaemon = true, name = "btproxy-udp-up") {
            while (keepRunning.get()) {
                try {
                    val packet = DatagramPacket(upBuf, upBuf.size)
                    relay.receive(packet)
                    senderRef[0] = packet.socketAddress
                    val parsed = parseSocksUdpPacket(packet.data, packet.offset, packet.length) ?: continue
                    stream.write(encodeUdpSmuxFrame(parsed.host, parsed.port, parsed.payload))
                } catch (_: SocketTimeoutException) {
                    // poll loop
                } catch (_: Exception) {
                    break
                }
            }
        }

        val smuxToLocal = thread(isDaemon = true, name = "btproxy-udp-down") {
            val input = stream.inputStream()
            while (keepRunning.get()) {
                try {
                    val incoming = decodeUdpSmuxFrame(input) ?: break
                    val target = senderRef[0] ?: continue
                    val payload = encodeSocksUdpPacket(incoming.host, incoming.port, incoming.payload)
                    val datagram = DatagramPacket(payload, payload.size, target)
                    relay.send(datagram)
                } catch (_: Exception) {
                    break
                }
            }
        }

        val now = System.currentTimeMillis()
        if (now - lastUdpLogAt > 3000) {
            lastUdpLogAt = now
            logger("SOCKS UDP_ASSOCIATE -> relay activo 127.0.0.1:$bindPort")
        }

        // Esperar cierre del control TCP de UDP_ASSOCIATE.
        while (true) {
            val b = cin.read()
            if (b < 0) break
        }
        keepRunning.set(false)
        runCatching { stream.close() }
        runCatching { relay.close() }
        localToSmux.join(1000)
        smuxToLocal.join(1000)
        runCatching { client.close() }
    }

    private data class UdpTarget(val host: String, val port: Int, val payload: ByteArray)

    private fun parseSocksUdpPacket(data: ByteArray, off: Int, len: Int): UdpTarget? {
        if (len < 10) return null
        var p = off
        val end = off + len
        val rsv = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
        p += 2
        val frag = data[p++].toInt() and 0xFF
        if (rsv != 0 || frag != 0) return null
        val atyp = data[p++].toInt() and 0xFF
        val host = when (atyp) {
            1 -> {
                if (p + 4 > end) return null
                "${data[p].toInt() and 0xFF}.${data[p + 1].toInt() and 0xFF}." +
                    "${data[p + 2].toInt() and 0xFF}.${data[p + 3].toInt() and 0xFF}".also { p += 4 }
            }
            3 -> {
                if (p >= end) return null
                val l = data[p++].toInt() and 0xFF
                if (p + l > end) return null
                String(data, p, l).also { p += l }
            }
            4 -> {
                if (p + 16 > end) return null
                val ipv6 = ByteArray(16)
                System.arraycopy(data, p, ipv6, 0, 16)
                p += 16
                InetAddress.getByAddress(ipv6).hostAddress ?: return null
            }
            else -> return null
        }
        if (p + 2 > end) return null
        val port = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
        p += 2
        if (p > end) return null
        val payload = ByteArray(end - p)
        System.arraycopy(data, p, payload, 0, payload.size)
        return UdpTarget(host, port, payload)
    }

    private fun encodeUdpSmuxFrame(host: String, port: Int, payload: ByteArray): ByteArray {
        val hostBytes = host.toByteArray()
        val bb = ByteArrayOutputStream(2 + hostBytes.size + 2 + 2 + payload.size)
        bb.write((hostBytes.size ushr 8) and 0xFF)
        bb.write(hostBytes.size and 0xFF)
        bb.write(hostBytes)
        bb.write((port ushr 8) and 0xFF)
        bb.write(port and 0xFF)
        bb.write((payload.size ushr 8) and 0xFF)
        bb.write(payload.size and 0xFF)
        bb.write(payload)
        return bb.toByteArray()
    }

    private fun decodeUdpSmuxFrame(input: InputStream): UdpTarget? {
        val hdr = readExact(input, 2) ?: return null
        val hostLen = ((hdr[0].toInt() and 0xFF) shl 8) or (hdr[1].toInt() and 0xFF)
        if (hostLen <= 0) return null
        val host = String(readExact(input, hostLen) ?: return null)
        val pp = readExact(input, 2) ?: return null
        val port = ((pp[0].toInt() and 0xFF) shl 8) or (pp[1].toInt() and 0xFF)
        val ll = readExact(input, 2) ?: return null
        val dataLen = ((ll[0].toInt() and 0xFF) shl 8) or (ll[1].toInt() and 0xFF)
        val payload = readExact(input, dataLen) ?: return null
        return UdpTarget(host, port, payload)
    }

    private fun encodeSocksUdpPacket(host: String, port: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0))
        val ip = runCatching { InetAddress.getByName(host) }.getOrNull()
        if (ip is Inet4Address) {
            out.write(1)
            out.write(ip.address)
        } else if (ip is Inet6Address) {
            out.write(4)
            out.write(ip.address)
        } else {
            val hb = host.toByteArray()
            out.write(3)
            out.write(hb.size)
            out.write(hb)
        }
        out.write((port ushr 8) and 0xFF)
        out.write(port and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    private fun readExact(input: InputStream, len: Int): ByteArray? {
        val b = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(b, off, len - off)
            if (n < 0) return null
            off += n
        }
        return b
    }

}
