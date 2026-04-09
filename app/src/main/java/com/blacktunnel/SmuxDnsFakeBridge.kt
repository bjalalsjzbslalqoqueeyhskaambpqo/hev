package com.blacktunnel

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Intermediario liviano SOCKS5 → túnel persistente (SMUX fake).
 *
 * Por ahora sólo puentea bytes TCP para reemplazar el motor Xray sin romper el flujo.
 * La capa de DNS fake se integrará luego.
 */
object SmuxDnsFakeBridge {

    @Volatile private var running = false
    @Volatile private var listener: ServerSocket? = null

    fun start(listenPort: Int, targetPort: Int) {
        if (running) return
        running = true
        thread(isDaemon = true, name = "smux-fake-accept") {
            try {
                val server = ServerSocket(listenPort, 256, InetAddress.getByName("127.0.0.1"))
                listener = server
                while (running) {
                    val incoming = server.accept().also { it.tcpNoDelay = true }
                    val upstream = runCatching {
                        Socket("127.0.0.1", targetPort).also { it.tcpNoDelay = true }
                    }.getOrElse {
                        runCatching { incoming.close() }
                        continue
                    }
                    forwardBothWays(incoming, upstream)
                }
            } catch (_: Exception) {
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        running = false
        runCatching { listener?.close() }
        listener = null
    }

    private fun forwardBothWays(left: Socket, right: Socket) {
        thread(isDaemon = true, name = "smux-fake-l2r") {
            pipe(left, right)
        }
        thread(isDaemon = true, name = "smux-fake-r2l") {
            pipe(right, left)
        }
    }

    private fun pipe(inputSide: Socket, outputSide: Socket) {
        val buf = ByteArray(65536)
        try {
            val inp = inputSide.getInputStream()
            val out = outputSide.getOutputStream()
            while (running) {
                val n = inp.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { inputSide.close() }
            runCatching { outputSide.close() }
        }
    }
}
