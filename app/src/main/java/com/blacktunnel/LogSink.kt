package com.blacktunnel

import com.blacktunnel.ui.screens.LogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object LogSink {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries = _entries.asStateFlow()

    fun clear() {
        _entries.value = emptyList()
    }

    fun add(icon: String, text: String, level: LogLevel = LogLevel.INFO) {
        val entry = LogEntry(icon = icon, text = text, color = level.color)
        _entries.value = (_entries.value + entry).takeLast(10)
    }
}

enum class LogLevel(val color: androidx.compose.ui.graphics.Color) {
    INFO(androidx.compose.ui.graphics.Color(0xFF94A3B8)),
    OK(androidx.compose.ui.graphics.Color(0xFFE2E8F0)),
    SUCCESS(androidx.compose.ui.graphics.Color(0xFF4ADE80)),
    WARN(androidx.compose.ui.graphics.Color(0xFFFBBF24)),
    ERROR(androidx.compose.ui.graphics.Color(0xFFFF4C6A))
}
