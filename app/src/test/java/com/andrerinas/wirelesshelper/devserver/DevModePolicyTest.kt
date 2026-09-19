package com.andrerinas.wirelesshelper.devserver

import org.junit.Assert.*
import org.junit.Test

class DevModePolicyTest {
    @Test fun completedStopReplacesStoppingText() {
        assertEquals("Stopped", DevModePolicy.statusAfterStop("Stopping", null, false, "Stopped"))
    }

    @Test fun incompleteCleanupAndFailuresAreNotShownAsSuccess() {
        assertEquals("Stopping", DevModePolicy.statusAfterStop("Stopping", null, true, "Stopped"))
        for (pending in listOf(false, true)) {
            assertEquals("Error", DevModePolicy.statusAfterStop("Stopping", "Error", pending, "Stopped"))
        }
    }

    @Test fun legacyModeDoesNotAcquireDevServerStatusOnStop() {
        assertNull(DevModePolicy.statusAfterStop(null, null, false, "Stopped"))
    }

    @Test fun repeatedStartDuringStartupOrAnActiveSessionIsIgnored() {
        assertEquals(5, DevModePolicy.MODE_ID)
        assertEquals("wifi-direct-dev-root", DevModePolicy.URI_MODE)
        assertTrue(DevModePolicy.reusesStart(5, 5, true))
        assertFalse(DevModePolicy.reusesStart(5, 5, false))
        assertFalse(DevModePolicy.reusesStart(5, null, true))
        for (legacy in 0..4) {
            assertFalse(DevModePolicy.reusesStart(5, legacy, true))
            assertFalse(DevModePolicy.reusesStart(legacy, 5, true))
        }
    }

    @Test fun repeatedDisconnectSchedulesOnlyRemainingGrace() {
        val policy = BluetoothStopPolicy()
        policy.connected("A")
        policy.disconnected("A", 0)
        assertEquals(5000L, policy.remainingDelay(0))
        policy.disconnected("A", 4000)
        assertEquals(1000L, policy.remainingDelay(4000))
        policy.connected("A")
        assertNull(policy.remainingDelay(4500))
    }
}
