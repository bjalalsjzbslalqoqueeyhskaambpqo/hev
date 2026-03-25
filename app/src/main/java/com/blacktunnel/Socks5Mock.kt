package com.blacktunnel

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

object Socks5Mock {
    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (serverSocket != null) {
            return
        }
        thread(name = "socks5-mock", isDaemon = true) {
            runCatching {
                val srv = ServerSocket()
                srv.reuseAddress = true
                srv.bind(InetSocketAddress("127.0.0.1", 10808))
                serverSocket = srv
                LogStore.add("SOCKS5 mock listening on 127.0.0.1:10808")
                while (!srv.isClosed) {
                    val client = srv.accept()
                    thread(name = "socks5-client", isDaemon = true) {
                        handle(client)
                    }
                }
            }.onFailure {
                LogStore.add("SOCKS5 mock stopped: ${it.message}")
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())

            val version = input.read()
            if (version != 0x05) {
                LogStore.add("Invalid SOCKS version: $version")
                return
            }
            val nMethods = input.read()
            val methods = ByteArray(nMethods)
            if (input.read(methods) != nMethods) return
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            val reqVersion = input.read()
            val cmd = input.read()
            input.read() // RSV
            val atyp = input.read()
            if (reqVersion != 0x05) return

            val destination = readDestination(input, atyp)
            val portBytes = ByteArray(2)
            if (input.read(portBytes) != 2) return
            val port = ((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)

            val cmdText = when (cmd) {
                0x01 -> "CONNECT"
                0x03 -> "UDP_ASSOCIATE"
                else -> "CMD_$cmd"
            }
            LogStore.add("SOCKS5 $cmdText -> $destination:$port")

            output.write(
                byteArrayOf(
                    0x05,
                    0x00,
                    0x00,
                    0x01,
                    127,
                    0,
                    0,
                    1,
                    0,
                    0
                )
            )
            output.flush()
        }
    }

    private fun readDestination(input: BufferedInputStream, atyp: Int): String {
        return when (atyp) {
            0x01 -> {
                val addr = ByteArray(4)
                input.read(addr)
                InetAddress.getByAddress(addr).hostAddress ?: "0.0.0.0"
            }

            0x03 -> {
                val len = input.read()
                val host = ByteArray(len)
                input.read(host)
                String(host)
            }

            0x04 -> {
                val addr = ByteArray(16)
                input.read(addr)
                InetAddress.getByAddress(addr).hostAddress ?: "::"
            }

            else -> "unknown"
        }
    }
}
