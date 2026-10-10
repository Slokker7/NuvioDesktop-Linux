package com.nuvio.app.features.player.desktop

import java.awt.Component
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxHeroHostGeometryTest {
    // No peer, Toolkit, Window, native handle or display connection. Invoke the
    // listeners directly to exercise the production adapter deterministically.
    private class Host : Component() {
        var displayable = true
        var showing = true
        var hostWidth = 1
        var hostHeight = 1
        override fun isDisplayable() = displayable
        override fun isShowing() = showing
        override fun getWidth() = hostWidth
        override fun getHeight() = hostHeight
        fun allocate(width: Int, height: Int) {
            hostWidth = width
            hostHeight = height
            val event = ComponentEvent(this, ComponentEvent.COMPONENT_RESIZED)
            componentListeners.forEach { it.componentResized(event) }
        }
        fun showHost() {
            showing = true
            val event = HierarchyEvent(this, HierarchyEvent.HIERARCHY_CHANGED, this, null, HierarchyEvent.SHOWING_CHANGED.toLong())
            hierarchyListeners.forEach { it.hierarchyChanged(event) }
        }
    }

    private fun gate(host: Host, onReady: () -> Unit = {}) =
        assertNotNull(LinuxHeroHostGeometry.install(DesktopHostOs.LINUX, true, host, onReady))

    @Test fun trivialGeometryCannotAttachEvenWhenDisplayable() {
        val host = Host()
        gate(host).use { gate ->
            for ((width, height) in listOf(0 to 0, 0 to 720, 1280 to 0, 1 to 1, 1 to 720, 1280 to 1)) {
                host.allocate(width, height)
                assertFalse(gate.canAttach(), "$width x $height")
            }
        }
    }

    @Test fun visibleAllocatedHeroCanAttachImmediately() {
        val host = Host().apply { allocate(1280, 720) }
        gate(host).use { assertTrue(it.canAttach()) }
    }

    @Test fun placeholderToValidAllocationRetriesExactlyOnceAndLaterResizesDoNotReattach() {
        val host = Host()
        var attaches = 0
        gate(host) { attaches++ }.use { gate ->
            assertFalse(gate.canAttach())
            host.allocate(1, 1)
            host.allocate(1280, 1)
            assertEquals(0, attaches)
            host.allocate(1280, 720)
            repeat(10) { host.allocate(1280 + it, 720) }
            host.showHost()
            assertEquals(1, attaches)
        }
    }

    @Test fun displayabilityAndShowingAreRequiredInAdditionToBounds() {
        val host = Host().apply { allocate(640, 360); displayable = false }
        var attaches = 0
        gate(host) { attaches++ }.use { gate ->
            assertFalse(gate.canAttach())
            host.displayable = true
            host.showing = false
            host.allocate(640, 360)
            assertEquals(0, attaches)
            host.showHost()
            assertEquals(1, attaches)
        }
    }

    @Test fun geometryLostBeforeQueuedAttachCanWaitForLayoutAgain() {
        val host = Host().apply { allocate(640, 360) }
        var retries = 0
        gate(host) { retries++ }.use { gate ->
            assertTrue(gate.canAttach()) // Initial request.
            host.allocate(1, 1)
            assertFalse(gate.canAttach()) // Controller's queued EDT recheck.
            host.allocate(640, 360)
            assertEquals(1, retries)
        }
    }

    @Test fun geometryChangesDuringNativeAcquisitionInvalidateReadinessUntilLayoutRecovers() {
        val host = Host().apply { allocate(640, 360) }
        var retries = 0
        gate(host) { retries++ }.use { gate ->
            assertTrue(gate.canAttach())
            host.allocate(1, 1)
            assertFalse(gate.ready) // Also read by the native worker's generation guard.
            host.allocate(640, 360)
            assertTrue(gate.ready)
            assertEquals(1, retries)
            gate.attached()
            host.allocate(1, 1)
            host.allocate(1920, 1080)
            assertEquals(1, retries) // A live handle resizes through the existing native path.
        }
    }

    @Test fun cancelledIneligibleRequestCannotReviveWhenGeometryArrives() {
        val host = Host()
        val lock = Any()
        val ownership = LinuxPlayerOwnership(lock)
        val owner = Any()
        val stale = assertNotNull(ownership.request(owner, LinuxPlayerOwnership.Priority.HERO))
        var retries = 0
        gate(host) { retries++ }.use { gate ->
            assertFalse(gate.canAttach())
            gate.cancel() // Hidden, navigated away or evicted, as in the controller.
            ownership.cancel(owner)
            host.allocate(1280, 720)
            assertEquals(0, retries)
            synchronized(lock) {
                assertFalse(ownership.claim(stale, release = {}))
            }
        }
    }

    @Test fun lateLayoutCannotPreemptFullPlayerOrQueueAResurrectionAfterItLeaves() {
        val host = Host()
        val lock = Any()
        val ownership = LinuxPlayerOwnership(lock)
        val hero = Any()
        val player = Any()
        var creates = 0
        gate(host) {
            ownership.request(hero, LinuxPlayerOwnership.Priority.HERO)?.let { request ->
                synchronized(lock) {
                    if (ownership.claim(request, release = {})) creates++
                }
            }
        }.use { gate ->
            assertFalse(gate.canAttach())
            val fullPlayer = assertNotNull(ownership.request(player, LinuxPlayerOwnership.Priority.PLAYER))
            synchronized(lock) { assertTrue(ownership.claim(fullPlayer, release = {})) }
            host.allocate(1280, 720)
            assertEquals(0, creates)
            ownership.cancel(player)
            synchronized(lock) { ownership.forget(player) }
            host.allocate(1281, 720)
            assertEquals(0, creates)
        }
    }

    @Test fun disposalRemovesListenersAndPendingRetry() {
        val host = Host()
        var retries = 0
        val gate = gate(host) { retries++ }
        assertFalse(gate.canAttach())
        gate.close()
        assertTrue(host.componentListeners.isEmpty())
        assertTrue(host.hierarchyListeners.isEmpty())
        host.allocate(1280, 720)
        assertEquals(0, retries)
    }

    @Test fun fullPlayerAndOtherPlatformsNeverInstallHeroGeometryGate() {
        val host = Host()
        assertNull(LinuxHeroHostGeometry.install(DesktopHostOs.LINUX, false, host) { error("Full player gated") })
        for (os in listOf(DesktopHostOs.WINDOWS, DesktopHostOs.MACOS)) {
            assertNull(LinuxHeroHostGeometry.install(os, true, host) { error("Other platform gated") })
        }
        assertTrue(host.componentListeners.isEmpty())
        assertTrue(host.hierarchyListeners.isEmpty())
    }

    @Test fun onlyActiveLinuxHeroesAllocateBeforePlaybackReadiness() {
        for (ready in listOf(false, true)) for (playing in listOf(false, true)) {
            assertEquals(playing, heroTrailerHostUsesFullBounds(DesktopHostOs.LINUX, ready, playing))
            for (os in listOf(DesktopHostOs.WINDOWS, DesktopHostOs.MACOS)) {
                assertEquals(ready && playing, heroTrailerHostUsesFullBounds(os, ready, playing))
            }
        }
    }
}
