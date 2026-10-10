package com.nuvio.app

import org.junit.AssumptionViolatedException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the permission boundary without initializing AWT or creating a window. */
class LiveDisplayTestsTest {
    @Test
    fun defaultLinuxWorkerHasNoDisplayAccess() {
        if (!System.getProperty("os.name").contains("linux", ignoreCase = true) ||
            System.getenv(LiveDisplayTests.OPT_IN_ENV) == "1") return
        assertTrue(java.awt.GraphicsEnvironment.isHeadless())
        assertNull(System.getenv("DISPLAY"))
        assertNull(System.getenv("WAYLAND_DISPLAY"))
    }

    @Test
    fun absentOrInexactOptInSkipsBeforeInspectingGraphics() {
        for (value in listOf(null, "", "0", "true", "yes", " 1", "1 ")) {
            val skip = assertFailsWith<AssumptionViolatedException> {
                LiveDisplayTests.assumeEnabled(value) { error("Must not inspect graphics without permission") }
            }
            assertTrue(skip.message.orEmpty().contains("NUVIO_RUN_LIVE_DISPLAY_TESTS=1"))
        }
    }

    @Test
    fun explicitOptInStillSkipsAHeadlessWorker() {
        val skip = assertFailsWith<AssumptionViolatedException> {
            LiveDisplayTests.assumeEnabled("1") { true }
        }
        assertTrue(skip.message.orEmpty().contains("non-headless display"))
    }

    @Test
    fun explicitOptInAllowsTheBodyOnlyWithANonHeadlessWorker() {
        // Fake availability: this test must never initialize a real graphics environment.
        LiveDisplayTests.assumeEnabled("1") { false }
    }
}
