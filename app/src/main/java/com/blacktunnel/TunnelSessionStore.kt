package com.blacktunnel

data class TunnelSessionSnapshot(
    val state: String = "DISCONNECTED",
    val status: String = "-",
    val name: String = "-",
    val daysLeft: String = "-",
    val latencyMs: Long = -1L
)

object TunnelSessionStore {
    private val lock = Any()
    private var snapshot = TunnelSessionSnapshot()
    private val listeners = mutableSetOf<(TunnelSessionSnapshot) -> Unit>()

    fun setState(state: String) {
        synchronized(lock) { snapshot = snapshot.copy(state = state) }
        notifyListeners()
    }

    fun setLatency(latencyMs: Long) {
        synchronized(lock) { snapshot = snapshot.copy(latencyMs = latencyMs) }
        notifyListeners()
    }

    fun updateFromHeaders(headers: Map<String, String>) {
        synchronized(lock) {
            snapshot = snapshot.copy(
                status = headers["X-Status"] ?: snapshot.status,
                name = headers["X-Name"] ?: snapshot.name,
                daysLeft = headers["X-Days-Left"] ?: snapshot.daysLeft
            )
        }
        notifyListeners()
    }

    fun reset() {
        synchronized(lock) { snapshot = TunnelSessionSnapshot() }
        notifyListeners()
    }

    fun current(): TunnelSessionSnapshot = synchronized(lock) { snapshot }

    fun addListener(listener: (TunnelSessionSnapshot) -> Unit) {
        synchronized(lock) { listeners.add(listener) }
        listener(current())
    }

    fun removeListener(listener: (TunnelSessionSnapshot) -> Unit) {
        synchronized(lock) { listeners.remove(listener) }
    }

    private fun notifyListeners() {
        val current = current()
        val activeListeners = synchronized(lock) { listeners.toList() }
        activeListeners.forEach { it(current) }
    }
}
