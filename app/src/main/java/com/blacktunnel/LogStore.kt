package com.blacktunnel

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogStore {
    private const val CAPACITY = 1500
    private val lock = Any()
    private val entries = ArrayDeque<String>(CAPACITY)
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(message: String) {
        val line = "${formatter.format(Date())} [${Thread.currentThread().name}] $message"
        synchronized(lock) {
            if (entries.size == CAPACITY) {
                entries.removeFirst()
            }
            entries.addLast(line)
        }
    }

    fun dump(): String = synchronized(lock) { entries.joinToString("\n") }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }
}
