package com.blacktunnel

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
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
private const val GLOBAL_OUTBOUND_BUFFER = 8 * 1024 * 1024
private const val STREAM_OUTBOUND_BUFFER = 512 * 1024
private const val STREAM_INBOUND_BUFFER = 512 * 1024
private const val STREAM_INBOUND_OFFER_TIMEOUT_MS = 250L
private const val STREAM_IDLE_TIMEOUT_MS = 120_000L

private enum class StreamPriority { HIGH, NORMAL, BULK }

private data class OutboundChunk(
    val streamId: Int,
    val payload: ByteArray,
    val priority: StreamPriority
)

class SmuxSession(
    private val socket: Socket,
    private val logger: (String) -> Unit
) {
    private val streams = ConcurrentHashMap<Int, SmuxStream>()
    private val nextStreamId = AtomicInteger(1)
    private val running = AtomicInteger(1)
    private val lastPongAt = AtomicLong(System.currentTimeMillis())

    private val globalCredits = Semaphore(GLOBAL_OUTBOUND_BUFFER)
    private val highQueue = LinkedBlockingQueue<OutboundChunk>()
    private val normalQueue = LinkedBlockingQueue<OutboundChunk>()
    private val bulkQueue = LinkedBlockingQueue<OutboundChunk>()

    val isOpen: Boolean
        get() = running.get() == 1 && !socket.isClosed

    init {
        socket.tcpNoDelay = true

        thread(isDaemon = true, name = "smux-reader") {
            runCatching { readerLoop() }
                .onFailure { logger("smux reader error: ${it.message}") }
            shutdownAll()
        }

        thread(isDaemon = true, name = "smux-writer") {
            runCatching { writerLoop() }
                .onFailure { logger("smux writer error: ${it.message}") }
            shutdownAll()
        }

        thread(isDaemon = true, name = "smux-keepalive") {
            while (isOpen) {
                Thread.sleep(15_000)
                runCatching { sendFrameDirect(CMD_PING, 0, ByteArray(0)) }
                val idle = System.currentTimeMillis() - lastPongAt.get()
                if (idle > 60_000) {
                    logger("smux timeout idle=${idle}ms")
                    close()
                    break
                }
            }
        }

        thread(isDaemon = true, name = "smux-stream-watchdog") {
            while (isOpen) {
                Thread.sleep(10_000)
                val now = System.currentTimeMillis()
                streams.values.forEach { s ->
                    if (now - s.lastActivityAt() > STREAM_IDLE_TIMEOUT_MS) {
                        logger("smux stream timeout sid=${s.id()} idleMs=${now - s.lastActivityAt()}")
                        s.close()
                    }
                }
            }
        }
    }

    fun openStream(priorityHint: Int = 0): SmuxStream {
        check(isOpen) { "smux session closed" }
        val sid = nextStreamId.getAndAdd(2)
        val stream = SmuxStream(
            session = this,
            streamId = sid,
            priority = when {
                priorityHint <= 0 -> StreamPriority.HIGH
                priorityHint < 10_000 -> StreamPriority.NORMAL
                else -> StreamPriority.BULK
            }
        )
        streams[sid] = stream
        sendFrameDirect(CMD_OPEN, sid, ByteArray(0))
        return stream
    }

    internal fun sendData(streamId: Int, data: ByteArray, off: Int, len: Int, priority: StreamPriority) {
        var cursor = off
        var remaining = len
        while (remaining > 0 && isOpen) {
            val chunk = min(remaining, SMUX_MAX_FRAME)
            globalCredits.acquire(chunk)
            val stream = streams[streamId]
            if (stream == null || !stream.isOpen()) {
                globalCredits.release(chunk)
                return
            }
            stream.acquireOutboundCredit(chunk)
            val payload = data.copyOfRange(cursor, cursor + chunk)
            enqueueOutbound(OutboundChunk(streamId, payload, priority))
            cursor += chunk
            remaining -= chunk
        }
    }

    internal fun closeStream(streamId: Int) {
        streams.remove(streamId)
        runCatching { sendFrameDirect(CMD_CLOSE, streamId, ByteArray(0)) }
    }

    internal fun onChunkWritten(streamId: Int, bytes: Int) {
        globalCredits.release(bytes)
        streams[streamId]?.releaseOutboundCredit(bytes)
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
        highQueue.clear()
        normalQueue.clear()
        bulkQueue.clear()
    }

    private fun enqueueOutbound(chunk: OutboundChunk) {
        when (chunk.priority) {
            StreamPriority.HIGH -> highQueue.put(chunk)
            StreamPriority.NORMAL -> normalQueue.put(chunk)
            StreamPriority.BULK -> bulkQueue.put(chunk)
        }
    }

    private fun writerLoop() {
        var normalBudget = 0
        var bulkBudget = 0
        while (isOpen) {
            val frame = pollFrame(normalBudget, bulkBudget) ?: continue
            sendFrameDirect(CMD_DATA, frame.streamId, frame.payload)
            onChunkWritten(frame.streamId, frame.payload.size)

            when (frame.priority) {
                StreamPriority.HIGH -> {
                    normalBudget += 1
                    bulkBudget += 1
                }

                StreamPriority.NORMAL -> {
                    normalBudget = maxOf(0, normalBudget - 1)
                    bulkBudget += 1
                }

                StreamPriority.BULK -> {
                    bulkBudget = maxOf(0, bulkBudget - 1)
                }
            }
        }
    }

    private fun pollFrame(normalBudget: Int, bulkBudget: Int): OutboundChunk? {
        highQueue.poll()?.let { return it }
        if (normalBudget > 0) {
            normalQueue.poll()?.let { return it }
        }
        if (bulkBudget > 0) {
            bulkQueue.poll()?.let { return it }
        }
        return highQueue.poll(100, TimeUnit.MILLISECONDS)
            ?: normalQueue.poll()
            ?: bulkQueue.poll()
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
                    streams.putIfAbsent(sid, SmuxStream(this, sid, StreamPriority.NORMAL))
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
                        sendFrameDirect(CMD_PING, 0, ByteArray(0))
                    }
                }

                else -> logger("smux cmd desconocido=$cmd sid=$sid len=$length")
            }
        }
    }

    private fun sendFrameDirect(cmd: Int, streamId: Int, payload: ByteArray) {
        if (!isOpen) return
        val header = ByteBuffer.allocate(8)
            .order(ByteOrder.BIG_ENDIAN)
            .put(SMUX_VERSION.toByte())
            .put(cmd.toByte())
            .putShort(payload.size.toShort())
            .putInt(streamId)
            .array()
        synchronized(socket) {
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
    private val streamId: Int,
    private val priority: StreamPriority
) {
    private val queue = LinkedBlockingQueue<Any>()
    private val eofMarker = Any()
    private val outboundCredits = Semaphore(STREAM_OUTBOUND_BUFFER)
    private val inboundBufferedBytes = AtomicInteger(0)
    private val activityTs = AtomicLong(System.currentTimeMillis())

    @Volatile
    private var closed = false
    private var currentChunk: ByteArray? = null
    private var currentOffset = 0

    fun id(): Int = streamId
    fun isOpen(): Boolean = !closed
    fun lastActivityAt(): Long = activityTs.get()

    fun write(bytes: ByteArray) {
        if (closed || bytes.isEmpty()) return
        touch()
        session.sendData(streamId, bytes, 0, bytes.size, priority)
    }

    internal fun acquireOutboundCredit(bytes: Int) {
        outboundCredits.acquire(bytes)
    }

    internal fun releaseOutboundCredit(bytes: Int) {
        outboundCredits.release(bytes)
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
            touch()
            if (currentOffset >= chunk.size) {
                inboundBufferedBytes.addAndGet(-chunk.size)
                currentChunk = null
            }
            return toCopy
        }
    }

    fun outputStream(): OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            val one = byteArrayOf(b.toByte())
            session.sendData(streamId, one, 0, 1, priority)
            touch()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            session.sendData(streamId, b, off, len, priority)
            touch()
        }

        override fun flush() {
            // frame-based: no-op
        }
    }

    internal fun onData(data: ByteArray) {
        if (closed) return
        while (!closed) {
            val used = inboundBufferedBytes.get()
            if (used + data.size <= STREAM_INBOUND_BUFFER) {
                val offered = runCatching {
                    queue.offer(data, STREAM_INBOUND_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }.getOrDefault(false)
                if (offered) {
                    inboundBufferedBytes.addAndGet(data.size)
                    touch()
                    return
                }
            } else {
                runCatching { Thread.sleep(10) }
            }
        }
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

    private fun touch() {
        activityTs.set(System.currentTimeMillis())
    }
}
