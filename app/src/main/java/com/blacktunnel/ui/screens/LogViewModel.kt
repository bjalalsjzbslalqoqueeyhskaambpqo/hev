package com.blacktunnel.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class LogViewModel : ViewModel() {
    enum class LogLevel { INFO, OK, SUCCESS, WARN, ERROR }

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries = _entries.asStateFlow()

    fun add(icon: String, text: String, level: LogLevel = LogLevel.INFO) {
        val color = when (level) {
            LogLevel.INFO -> Color(0xFF94A3B8)
            LogLevel.OK -> Color(0xFFE2E8F0)
            LogLevel.SUCCESS -> Color(0xFF4ADE80)
            LogLevel.WARN -> Color(0xFFFBBF24)
            LogLevel.ERROR -> Color(0xFFFF4C6A)
        }
        _entries.value = (_entries.value + LogEntry(icon, text, color)).takeLast(8)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
