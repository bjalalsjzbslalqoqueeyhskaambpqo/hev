package com.nexora.ui.screens

import androidx.lifecycle.viewModelScope
import com.nexora.LogLevel
import com.nexora.LogSink
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class LogViewModel : ViewModel() {
    val entries = LogSink.entries.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    fun add(icon: String, text: String, level: LogLevel = LogLevel.INFO) {
        LogSink.add(icon, text, level)
    }

    fun clear() {
        LogSink.clear()
    }
}
