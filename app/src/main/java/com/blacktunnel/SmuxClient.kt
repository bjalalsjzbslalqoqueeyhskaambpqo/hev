package com.blacktunnel

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.min

private const val SMUX_VERSION: Int = 1
private const val CMD_OPEN: Int = 0
private const val CMD_CLOSE: Int = 1
private const val CMD_DATA: Int = 2
private const val CMD_PING: Int = 3
private const val SMUX_MAX_FRAME = 32 * 1024

class SmuxSession(
    private val socket: Socket,
    private val logger: (String) -> Unit
) {
    private val streams = ConcurrentHashMap<Int, SmuxStream>()
    private val writeLock = Any()
    private val nextStreamId = AtomicInteger(1)
    private val running = AtomicInteger(1)
    private val lastPongAt = AtomicLong(System.currentTimeMillis())

    val isOpen: Boolean
        get() = running.get() == 1 && !socket.isClosed

    init {
        socket.tcpNoDelay = true

        thread(isDaemon = true, name = "smux-reader") {
            runCatching { readerLoop() }
                .onFailure { logger("smux reader error: ${it.message}") }
            shutdownAll()
        }

        thread(isDaemon = true, name = "smux-keepalive") {
            while (isOpen) {
                Thread.sleep(15_000)
                runCatching { sendFrame(CMD_PING, 0, ByteArray(0)) }
                val idle = System.currentTimeMillis() - lastPongAt.get()
                if (idle > 60_000) {
                    logger("smux timeout idle=${idle}ms")
                    close()
                    break
                }
            }
        }
    }

    fun openStream(): SmuxStream {
        check(isOpen) { "smux session closed" }
        val sid = nextStreamId.getAndAdd(2)
        val stream = SmuxStream(this, sid)
        streams[sid] = stream
        sendFrame(CMD_OPEN, sid, ByteArray(0))
        return stream
    }

    internal fun sendData(streamId: Int, data: ByteArray, off: Int, len: Int) {
        var cursor = off
        var remaining = len
        while (remaining > 0) {
            val chunk = min(remaining, SMUX_MAX_FRAME)
            sendFrame(CMD_DATA, streamId, data.copyOfRange(cursor, cursor + chunk))
            cursor += chunk
            remaining -= chunk
        }
    }

    internal fun closeStream(streamId: Int) {
        streams.remove(streamId)
        runCatching { sendFrame(CMD_CLOSE, streamId, ByteArray(0)) }
    }

    fun close() {
        if (running.getAndSet(0) == 0) {
            return
        }
        runCatching { socket.close() }
        shutdownAll()
    }

    private fun shutdownAll() {
        streams.values.forEach { it.remoteClosed() }
        streams.clear()
    }

    private fun readerLoop() {
        val input = socket.getInputStream()
        while (isOpen) {
            val header = readExact(input, 8)
            val bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            val version = bb.get().toInt() and 0xFF
            val cmd = bb.get().toInt() and 0xFF
            val length = bb.short.toInt() and 0xFFFF
            val sid = bb.int

            if (version != SMUX_VERSION) {
                throw IllegalStateException("smux version inválida=$version")
            }

            val payload = if (length > 0) readExact(input, length) else ByteArray(0)
            when (cmd) {
                CMD_OPEN -> {
                    streams.putIfAbsent(sid, SmuxStream(this, sid))
                }

                CMD_CLOSE -> {
                    streams.remove(sid)?.remoteClosed()
                }

                CMD_DATA -> {
                    streams[sid]?.onData(payload)
                }

                CMD_PING -> {
                    lastPongAt.set(System.currentTimeMillis())
                    if (sid == 0) {
                        sendFrame(CMD_PING, 0, ByteArray(0))
                    }
                }

                else -> logger("smux cmd desconocido=$cmd sid=$sid len=$length")
            }
        }
    }

    private fun sendFrame(cmd: Int, streamId: Int, payload: ByteArray) {
        if (!isOpen) return
        val header = ByteBuffer.allocate(8)
            .order(ByteOrder.BIG_ENDIAN)
            .put(SMUX_VERSION.toByte())
            .put(cmd.toByte())
            .putShort(payload.size.toShort())
            .putInt(streamId)
            .array()
        synchronized(writeLock) {
            val out = socket.getOutputStream()
            out.write(header)
            if (payload.isNotEmpty()) out.write(payload)
            out.flush()
        }
    }

    private fun readExact(input: InputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw EOFException("smux eof")
            off += n
        }
        return buf
    }
}

class SmuxStream(
    private val session: SmuxSession,
    private val streamId: Int
) {
    private val queue = LinkedBlockingQueue<Any>()
    private val eofMarker = Any()
    @Volatile
    private var closed = false
    private var currentChunk: ByteArray? = null
    private var currentOffset = 0

    fun write(bytes: ByteArray) {
        if (closed) return
        session.sendData(streamId, bytes, 0, bytes.size)
    }

    fun inputStream(): InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed && currentChunk == null && queue.isEmpty()) return -1
            while (currentChunk == null || currentOffset >= currentChunk!!.size) {
                val next = queue.take()
                if (next === eofMarker) return -1
                currentChunk = next as ByteArray
                currentOffset = 0
            }
            val chunk = currentChunk!!
            val toCopy = min(len, chunk.size - currentOffset)
            System.arraycopy(chunk, currentOffset, b, off, toCopy)
            currentOffset += toCopy
            if (currentOffset >= chunk.size) {
                currentChunk = null
            }
            return toCopy
        }
    }

    fun outputStream(): OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            val one = byteArrayOf(b.toByte())
            session.sendData(streamId, one, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            session.sendData(streamId, b, off, len)
        }

        override fun flush() {
            // frame-based: no-op
        }
    }

    internal fun onData(data: ByteArray) {
        if (!closed) queue.offer(data)
    }

    internal fun remoteClosed() {
        closed = true
        queue.offer(eofMarker)
    }

    fun close() {
        if (closed) return
        closed = true
        session.closeStream(streamId)
        queue.offer(eofMarker)
    }
}
