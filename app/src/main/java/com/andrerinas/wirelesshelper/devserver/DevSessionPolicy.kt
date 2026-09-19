package com.andrerinas.wirelesshelper.devserver

class DevSessionPolicy {
    enum class Action { NONE, START, STOP, RESTART }
    private var host: String? = null
    private var lostAt: Long? = null
    private var waitingSince = 0L
    private var tcpWasConnected = false
    private var tcpLostAt: Long? = null
    private var retryDelay = 30_000L
    private var retryAfter = 0L

    fun onPeer(host: String?, now: Long) {
        if (this.host == host) return
        this.host = host
        lostAt = if (host == null) now else null
        waitingSince = now
        tcpWasConnected = false
        tcpLostAt = null
        retryAfter = 0
        retryDelay = 30_000
    }

    fun action(now: Long, state: DevServerState): Action {
        val peer = host
        if (peer == null) {
            return if (lostAt?.let { now - it >= 5000 } == true && !state.stopped) Action.STOP else Action.NONE
        }
        if (state.connectedTo(peer)) {
            tcpWasConnected = true
            tcpLostAt = null
            waitingSince = now
            retryDelay = 30_000
            retryAfter = 0
            return Action.NONE
        }
        // A connection to another peer is still an active AA session; never cycle underneath it.
        if (state.hasConnection || now < retryAfter) return Action.NONE
        if (tcpWasConnected) {
            if (tcpLostAt == null) tcpLostAt = now
            return if (now - tcpLostAt!! >= 2000) Action.RESTART else Action.NONE
        }
        if (!state.ready) return Action.START
        return if (now - waitingSince >= retryDelay) Action.RESTART else Action.NONE
    }

    fun attempted(now: Long, failed: Boolean = false) {
        tcpWasConnected = false
        tcpLostAt = null
        waitingSince = now
        retryAfter = if (failed) now + retryDelay else 0
        retryDelay = minOf(retryDelay * 2, 60_000)
    }
}

class BluetoothStopPolicy(private val graceMs: Long = 5000) {
    private val connected = mutableSetOf<String>()
    private var stopAt: Long? = null

    fun connected(mac: String) { connected.add(mac); stopAt = null }
    fun disconnected(mac: String, now: Long) {
        connected.remove(mac)
        if (connected.isEmpty() && stopAt == null) stopAt = now + graceMs
    }
    fun clear() { connected.clear(); stopAt = null }
    fun shouldStop(now: Long): Boolean = connected.isEmpty() && stopAt?.let { now >= it } == true
    fun remainingDelay(now: Long): Long? = if (connected.isEmpty()) stopAt?.let { maxOf(0, it - now) } else null
}
