package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxCompactWindowChromeTest {

    @Test fun repeatedCallsKeepOneNativeSession() {
        var begins = 0
        val closes = mutableListOf<Pair<Long, Long>>()
        val session = LinuxCompactChromeSession({ begins++; 77L }, { token, xid -> closes += token to xid; true })
        repeat(3) {
            repeat(5) { assertTrue(session.setCompact(123, true)) }
            repeat(5) { assertTrue(session.setCompact(123, false)) }
        }
        assertEquals(3, begins)
        assertEquals(List(3) { 77L to 123L }, closes)
        assertFalse(session.active)
    }
    @Test fun failedEnableCanBeRetriedAndMissingDrawableDoesNotCallNative() {
        var begins = 0
        val session = LinuxCompactChromeSession({ begins++; if (begins == 1) 0L else 7L }, { _, _ -> true })
        assertFalse(session.setCompact(0, true))
        assertEquals(0, begins)
        assertFalse(session.setCompact(123, true))
        assertTrue(session.setCompact(123, true))
        assertEquals(2, begins)
    }
    @Test fun failedRestoreRetainsStateForRetry() {
        var attempts = 0
        val session = LinuxCompactChromeSession({ 7 }, { _, _ -> ++attempts > 1 })
        assertTrue(session.setCompact(123, true))
        assertFalse(session.setCompact(123, false))
        assertTrue(session.active)
        assertTrue(session.setCompact(123, false))
        assertFalse(session.active)
    }
    @Test fun disposalNeverUsesFormerDrawable() {
        val closes = mutableListOf<Pair<Long, Long>>()
        val session = LinuxCompactChromeSession({ 7 }, { token, xid -> closes += token to xid; true })
        session.setCompact(123, true)
        session.abandon()
        session.abandon()
        assertEquals(listOf(7L to 0L), closes)
        assertFalse(session.active)
    }
}
