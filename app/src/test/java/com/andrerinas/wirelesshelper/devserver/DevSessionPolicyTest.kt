package com.andrerinas.wirelesshelper.devserver

import com.andrerinas.wirelesshelper.devserver.DevSessionPolicy.Action.*
import org.junit.Assert.*
import org.junit.Test

class DevSessionPolicyTest {
    private val ready = DevServerState(true, listOf(DevServerSocket("0.0.0.0", "0.0.0.0", "0A")))
    private val connected = DevServerState(true, ready.sockets + DevServerSocket("10.42.0.2", "10.42.0.1", "01"))
    private val stopped = DevServerState(false, emptyList())
    private fun policy() = DevSessionPolicy().apply { onPeer("10.42.0.1", 0) }

    @Test fun establishedTcpIsNeverRestartedByIdleTimer() {
        val policy = policy()
        for (time in 0L..600_000L step 1000) assertEquals(NONE, policy.action(time, connected))
    }

    @Test fun tcpLossHasTwoSecondGraceAndReturnCancelsRecovery() {
        val policy = policy()
        policy.action(0, connected)
        assertEquals(NONE, policy.action(1000, ready))
        assertEquals(NONE, policy.action(2999, ready))
        assertEquals(RESTART, policy.action(3000, ready))
        assertEquals(NONE, policy.action(3000, connected))
        assertEquals(NONE, policy.action(3001, ready))
    }

    @Test fun idleRetryBacksOffToSixtySeconds() {
        val policy = policy()
        assertEquals(NONE, policy.action(29_999, ready))
        assertEquals(RESTART, policy.action(30_000, ready))
        policy.attempted(30_000)
        assertEquals(NONE, policy.action(89_999, ready))
        assertEquals(RESTART, policy.action(90_000, ready))
        policy.attempted(90_000)
        assertEquals(RESTART, policy.action(150_000, ready))
    }

    @Test fun lossOfP2pStopsAfterFiveSecondsThenRestartsOnReturn() {
        val policy = policy()
        policy.onPeer(null, 1000)
        assertEquals(NONE, policy.action(5999, connected))
        assertEquals(STOP, policy.action(6000, connected))
        policy.attempted(6000)
        assertEquals(NONE, policy.action(7000, stopped))
        policy.onPeer("10.42.0.1", 8000)
        assertEquals(START, policy.action(8000, stopped))
    }

    @Test fun briefP2pLossDoesNotStopServer() {
        val policy = policy()
        policy.onPeer(null, 1000)
        policy.onPeer("10.42.0.1", 5999)
        assertEquals(NONE, policy.action(6000, connected))
    }

    @Test fun failuresAreDelayedButAnEstablishedConnectionAlwaysWins() {
        val policy = policy()
        policy.attempted(1000, failed = true)
        assertEquals(NONE, policy.action(30_999, stopped))
        assertEquals(START, policy.action(31_000, stopped))
        assertEquals(NONE, policy.action(31_000, connected))
        assertEquals(NONE, policy.action(300_000, connected))
    }

    @Test fun duplicatePeerEventsDoNotResetIdleDeadline() {
        val policy = policy()
        policy.onPeer("10.42.0.1", 29_000)
        assertEquals(RESTART, policy.action(30_000, ready))
    }

    @Test fun connectionToAnotherPeerIsNotInterrupted() {
        assertEquals(NONE, policy().action(300_000,
            DevServerState(true, listOf(DevServerSocket("192.168.1.2", "192.168.1.1", "01")))))
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
