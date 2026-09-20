package com.andrerinas.wirelesshelper.devserver

import com.andrerinas.wirelesshelper.net.P2pLink

/** Main-thread session decisions, independent of root polling and socket IO. */
class DevSessionPolicy {
    private var stopped = false
    private var peer: P2pLink? = null
    private var epoch = 0L
    private var attemptEpoch = 0L
    private var nextToken = 0L
    private var attempt: Long? = null
    private var connectedAt: Long? = null
    private var lostAt: Long? = null
    private var retryAt = 0L
    private var failureCount = 0
    var restartRequired = false
        private set

    fun onPeer(peer: P2pLink?, now: Long): Boolean {
        if (stopped || this.peer == peer) return false
        this.peer = peer
        epoch++
        lostAt = if (peer == null) now else null
        return true
    }

    fun beginAttempt(now: Long): Long? {
        if (stopped || peer == null || attempt != null || now < retryAt) return null
        attemptEpoch = epoch
        return (++nextToken).also { attempt = it; connectedAt = null }
    }

    fun isCurrent(token: Long): Boolean = !stopped && peer != null && attemptEpoch == epoch && attempt == token
    fun connected(token: Long, now: Long) { if (isCurrent(token)) connectedAt = now }

    fun finished(token: Long, now: Long, recoverServer: Boolean, failed: Boolean = true) {
        if (attempt != token) return
        attempt = null
        restartRequired = restartRequired || recoverServer
        if (connectedAt?.let { now - it >= 60_000 } == true) failureCount = 0
        connectedAt = null
        if (failed) {
            val delays = longArrayOf(2000, 4000, 8000, 16_000, 30_000, 60_000)
            retryAt = now + delays[failureCount.coerceAtMost(delays.lastIndex)]
            failureCount = (failureCount + 1).coerceAtMost(delays.lastIndex)
        }
    }

    fun waitForOtherSession(token: Long, now: Long) {
        finished(token, now, recoverServer = false, failed = false)
        retryAt = now + 2000
    }

    fun shouldStopServer(now: Long): Boolean = !stopped && peer == null &&
        lostAt?.let { now - it >= 5000 } == true

    fun serverRecovered() { restartRequired = false }
    fun stop() { stopped = true; epoch++ }
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
