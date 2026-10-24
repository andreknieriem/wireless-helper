package com.andrerinas.wirelesshelper.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidAutoTriggerPolicyTest {

    // The version a Galaxy Z Fold3 ran on 2026-10-23, while the helper searched for 14 hours.
    @Test
    fun `android auto 17_4 and later refuse the start request`() {
        assertEquals(false, AndroidAutoTriggerPolicy.acceptsAppTrigger("17.4.663034-release"))
        assertEquals(false, AndroidAutoTriggerPolicy.acceptsAppTrigger("17.8.663874-release"))
        assertEquals(false, AndroidAutoTriggerPolicy.acceptsAppTrigger("18.0.100000-release"))
    }

    @Test
    fun `android auto before 17_4 still takes it`() {
        assertEquals(true, AndroidAutoTriggerPolicy.acceptsAppTrigger("17.3.662000-release"))
        assertEquals(true, AndroidAutoTriggerPolicy.acceptsAppTrigger("16.9.660000-release"))
        assertEquals(true, AndroidAutoTriggerPolicy.acceptsAppTrigger("9.1.6100-release"))
    }

    // Minor 10 and up must compare as numbers: "17.10" is after "17.4", not before it.
    @Test
    fun `a two-digit minor version compares as a number`() {
        assertEquals(false, AndroidAutoTriggerPolicy.acceptsAppTrigger("17.10.1-release"))
        assertEquals(true, AndroidAutoTriggerPolicy.acceptsAppTrigger("16.10.1-release"))
    }

    @Test
    fun `the release a person would name is major dot minor`() {
        assertEquals("17.8", AndroidAutoTriggerPolicy.release("17.8.663874-release"))
        assertEquals("17.10", AndroidAutoTriggerPolicy.release("17.10.1-release"))
        assertNull(AndroidAutoTriggerPolicy.release(null))
        assertNull(AndroidAutoTriggerPolicy.release("release"))
    }

    // Not installed, or a name nobody can read: say nothing rather than guess.
    @Test
    fun `a missing or unreadable version is unknown`() {
        assertNull(AndroidAutoTriggerPolicy.acceptsAppTrigger(null))
        assertNull(AndroidAutoTriggerPolicy.acceptsAppTrigger(""))
        assertNull(AndroidAutoTriggerPolicy.acceptsAppTrigger("release"))
        assertNull(AndroidAutoTriggerPolicy.acceptsAppTrigger("17"))
    }
}
