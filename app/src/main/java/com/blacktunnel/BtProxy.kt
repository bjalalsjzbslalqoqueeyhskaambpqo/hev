package com.blacktunnel

import android.content.Context
import java.io.*
import java.net.*
import kotlin.concurrent.thread

object BtProxy {

    private const val PROXY_IPV6  = "2606:4700::6812:16b7"
    private const val PROXY_HOST  = "emailmarketing.personal.com.ar"
    private const val PROXY_PORT  = 80
    private const val TUNNEL_HOST = "7.brawlpass.com.ar"

    // Puerto local donde gost escucha SOCKS5 para HEV
    private const val GOST_SOCKS5_PORT = 10808
    // Puerto local donde BtProxy escucha para gost
    private const val TUNNEL_LOCAL_PORT = 10809

    @Volatile private var tunnelSocket: Socket? = null
    @Volatile private var tunnelIn: InputStream? = null
    @Volatile private var tunnelOut: OutputStream? = null
    @Volatile private var gostProcess: Process? = null
    @Volatile private var running = false

    fun start(
        ctx: Context,
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ) {
        running = true
        logger("BtProxy.start()")

        thread(isDaemon = true, name = "btproxy-init") {
            // 1. Abrir túnel TCP al servidor
            val sock = openTunnel(protectSocket, logger) ?: run {
                logger("ERROR no se pudo abrir túnel")
                return@thread
            }
            tunnelSocket = sock
            tunnelIn  = sock.getInputStream()
            tunnelOut = sock.getOutputStream()
            logger("Túnel TCP abierto")

            // 2. Arrancar servidor local que hace de bridge entre gost y el túnel
            startTunnelBridge(logger)

            // 3. Copiar y ejecutar gost
            startGost(ctx, logger)
        }
    }

    fun stop() {
        running = false
        gostProcess?.destroy()
        gostProcess = null
        runCatching { tunnelSocket?.close() }
        tunnelSocket = null
    }

    // Bridge local: escucha en TUNNEL_LOCAL_PORT, todo lo que llega
    // lo reenvía por el túnel TCP y viceversa
    private fun startTunnelBridge(logger: (String) -> Unit) {
        val srv = ServerSocket(TUNNEL_LOCAL_PORT, 4,
            InetAddress.getByName("127.0.0.1"))
        logger("Bridge escuchando en 127.0.0.1:$TUNNEL_LOCAL_PORT")

        thread(isDaemon = true, name = "bridge-accept") {
            try {
                while (running) {
                    val client = srv.accept()
                    client.tcpNoDelay = true
                    val tSock = tunnelSocket
                    if (tSock == null) {
                        client.close()
                        continue
                    }
                    relay(client, tSock, logger)
                }
            } catch (_: Exception) {}
        }
    }

    private fun relay(client: Socket, tunnel: Socket, logger: (String) -> Unit) {
        val buf = ByteArray(65536)
        // gost → túnel
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
        // túnel → gost
        thread(isDaemon = true) {
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
        }
    }

    // Ejecutar gost desde nativeLibraryDir como libgost.so
    private fun startGost(ctx: Context, logger: (String) -> Unit) {
        try {
            val nativeLibDir = ctx.applicationInfo.nativeLibraryDir
            val gostFile = File(nativeLibDir, "libgost.so")
            if (!gostFile.exists()) {
                logger("ERROR gost no encontrado en $nativeLibDir/libgost.so")
                return
            }

            // gost: SOCKS5 inbound en 10808, forward a bridge en 10809
            // notls=true → sin cifrado, sin overhead
            val cmd = listOf(
                gostFile.absolutePath,
                "-L", "socks5://:$GOST_SOCKS5_PORT",
                "-F", "relay+tcp://127.0.0.1:$TUNNEL_LOCAL_PORT?notls=true"
            )

            logger("Iniciando gost: ${cmd.joinToString(" ")}")

            val process = ProcessBuilder(cmd)
                .directory(File(nativeLibDir))
                .redirectErrorStream(true)
                .start()

            gostProcess = process

            // Log de gost en hilo separado
            thread(isDaemon = true, name = "gost-log") {
                process.inputStream.bufferedReader().forEachLine { line ->
                    logger("[gost] $line")
                }
            }

            logger("gost proceso iniciado")

        } catch (e: Exception) {
            logger("ERROR iniciando gost: ${e.message}")
        }
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
            val inp = sock.getInputStream()

            val p1 = "GET / HTTP/1.1\r\nHost: $PROXY_HOST\r\n\r\n"
            out.write(p1.toByteArray()); out.flush()
            logger("TX p1 host=$PROXY_HOST")
            Thread.sleep(300)

            val p2 = "- / HTTP/1.1\r\nHost: $TUNNEL_HOST\r\nUpgrade: websocket\r\nAction: tunnel\r\n\r\n"
            out.write(p2.toByteArray()); out.flush()
            logger("TX p2 host=$TUNNEL_HOST action=tunnel")

            sock.soTimeout = 8000
            val buf = ByteArrayOutputStream()
            var bloques = 0
            val deadline = System.currentTimeMillis() + 8000
            while (bloques < 2 && System.currentTimeMillis() < deadline) {
                try {
                    val tmp = ByteArray(4096)
                    val n = inp.read(tmp)
                    if (n < 0) break
                    buf.write(tmp, 0, n)
                    bloques = buf.toString().split("\r\n\r\n").size - 1
                } catch (_: java.net.SocketTimeoutException) { break }
            }

            val resp = buf.toString()
            logger("RX $bloques bloques: ${resp.take(100)}")

            if (!resp.contains("X-Status: OK", ignoreCase = true)) {
                logger("ERROR túnel rechazado")
                sock.close()
                return null
            }

            sock.soTimeout = 0
            logger("Túnel OK via IPv6 $PROXY_IPV6")
            sock

        } catch (e: Exception) {
            logger("ERROR abriendo túnel: ${e.message}")
            null
        }
    }
}
