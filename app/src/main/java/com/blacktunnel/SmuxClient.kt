package com.blacktunnel

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

class SmuxSession(
    private val socket: Socket,
    private val logger: (String) -> Unit
) {
    private val lock = Any()

    val isOpen: Boolean
        get() = !socket.isClosed

    fun openStream(): SmuxStream {
        logger("smux openStream")
        return SmuxStream(socket, lock)
    }

    fun close() {
        runCatching { socket.close() }
    }
}

class SmuxStream(
    private val socket: Socket,
    private val lock: Any
) {
    fun write(bytes: ByteArray) {
        synchronized(lock) {
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        }
    }

    fun inputStream(): InputStream = socket.getInputStream()

    fun outputStream(): OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            synchronized(lock) {
                socket.getOutputStream().write(b)
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(lock) {
                socket.getOutputStream().write(b, off, len)
            }
        }

        override fun flush() {
            synchronized(lock) {
                socket.getOutputStream().flush()
            }
        }
    }

    fun close() {
        runCatching { socket.close() }
    }
}
