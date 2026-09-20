package com.andrerinas.wirelesshelper.devserver

import com.andrerinas.wirelesshelper.net.P2pLink
import org.junit.Assert.*
import org.junit.Test

class DevSessionPolicyTest {
    private val peer = P2pLink("192.0.2.1", "192.0.2.2", "p2p-test")
    private fun policy() = DevSessionPolicy().apply { onPeer(peer, 0) }

    @Test fun onlyOneAttemptAndNoIdleRestartOfHealthyBridge() {
        val policy = policy()
        val token = policy.beginAttempt(0)!!
        policy.connected(token, 1)
        for (time in 0L..600_000L step 1000) assertNull(policy.beginAttempt(time))
        assertTrue(policy.isCurrent(token))
        assertFalse(policy.restartRequired)
    }

    @Test fun retryBackoffIsBoundedAndDuplicateEventsDoNotResetIt() {
        val policy = policy()
        var now = 0L
        for (delay in listOf(2000L, 4000L, 8000L, 16_000L, 30_000L, 60_000L, 60_000L)) {
            val token = policy.beginAttempt(now)!!
            policy.finished(token, now, recoverServer = false)
            assertFalse(policy.onPeer(peer, now + 1))
            assertNull(policy.beginAttempt(now + delay - 1))
            assertFalse(policy.restartRequired) // Unavailable head unit never cycles AA.
            now += delay
        }
        assertNotNull(policy.beginAttempt(now))
    }

    @Test fun sixtySecondsOfContinuousBridgeResetsBackoff() {
        val policy = policy()
        policy.finished(policy.beginAttempt(0)!!, 0, recoverServer = true)
        assertTrue(policy.restartRequired)
        policy.serverRecovered()
        assertFalse(policy.restartRequired)
        val token = policy.beginAttempt(2000)!!
        policy.connected(token, 2000)
        policy.finished(token, 62_000, recoverServer = true)
        assertNull(policy.beginAttempt(63_999))
        assertNotNull(policy.beginAttempt(64_000))
    }

    @Test fun foreignSessionWaitDoesNotConsumeRetryBudget() {
        val policy = policy()
        repeat(5) { index ->
            val now = index * 2000L
            policy.waitForOtherSession(policy.beginAttempt(now)!!, now)
            assertNull(policy.beginAttempt(now + 1999))
        }
        val token = policy.beginAttempt(10_000)!!
        policy.finished(token, 10_000, recoverServer = false)
        assertNotNull(policy.beginAttempt(12_000))
    }

    @Test fun p2pLossInvalidatesOldAttemptEvenIfSameGroupReturns() {
        val policy = policy()
        val old = policy.beginAttempt(0)!!
        policy.onPeer(null, 1000)
        assertFalse(policy.isCurrent(old))
        assertFalse(policy.shouldStopServer(5999))
        assertTrue(policy.shouldStopServer(6000))
        policy.onPeer(peer, 6001)
        assertFalse(policy.shouldStopServer(6002))
        assertNull(policy.beginAttempt(6002)) // Old IO must finish first.
        policy.finished(old, 6002, recoverServer = true, failed = false)
        val next = policy.beginAttempt(6002)!!
        assertNotEquals(old, next)
        assertFalse(policy.isCurrent(old))
    }

    @Test fun briefLossAndRepeatedLossEventsDoNotExtendGrace() {
        val policy = policy()
        policy.onPeer(null, 1000)
        policy.onPeer(null, 5000)
        assertTrue(policy.shouldStopServer(6000))
        policy.onPeer(peer, 6001)
        policy.onPeer(null, 7000)
        policy.onPeer(peer, 11_999)
        assertFalse(policy.shouldStopServer(12_000))
    }

    @Test fun addressOrInterfaceChangeInvalidatesAttempt() {
        val policy = policy()
        val token = policy.beginAttempt(0)!!
        assertTrue(policy.onPeer(peer.copy(localAddress = "192.0.2.3"), 1))
        assertFalse(policy.isCurrent(token))
    }

    @Test fun stopBlocksAllLateCallbacksAndRetries() {
        val policy = policy()
        val token = policy.beginAttempt(0)!!
        policy.stop()
        policy.finished(token, 1, recoverServer = true)
        assertFalse(policy.onPeer(peer, 2))
        policy.connected(token, 3)
        assertFalse(policy.isCurrent(token))
        assertNull(policy.beginAttempt(600_000))
        assertFalse(policy.shouldStopServer(600_000))
    }

    @Test fun retryOnSameNetworkRejectsPreviousAttemptCallbacks() {
        val policy = policy()
        val old = policy.beginAttempt(0)!!
        policy.finished(old, 0, recoverServer = false)
        val next = policy.beginAttempt(2000)!!
        assertNotEquals(old, next)
        policy.connected(old, 2001)
        policy.finished(old, 2002, recoverServer = true)
        assertTrue(policy.isCurrent(next))
        assertFalse(policy.restartRequired)
        assertNull(policy.beginAttempt(5000))
    }

    @Test fun bluetoothBounceCancelsStop() {
        val policy = BluetoothStopPolicy()
        policy.connected("A")
        policy.disconnected("A", 1000)
        assertFalse(policy.shouldStop(5999))
        policy.connected("A")
        assertFalse(policy.shouldStop(6000))
    }

    @Test fun onlyLastSelectedBluetoothDisconnectStops() {
        val policy = BluetoothStopPolicy()
        policy.connected("A")
        policy.connected("B")
        policy.disconnected("A", 1000)
        assertFalse(policy.shouldStop(6000))
        policy.disconnected("B", 7000)
        assertFalse(policy.shouldStop(11_999))
        assertTrue(policy.shouldStop(12_000))
    }

    @Test fun repeatedDisconnectCannotKeepExtendingGrace() {
        val policy = BluetoothStopPolicy()
        policy.connected("A")
        policy.disconnected("A", 0)
        policy.disconnected("A", 4000)
        assertTrue(policy.shouldStop(5000))
        policy.clear()
        assertFalse(policy.shouldStop(6000))
    }
}
