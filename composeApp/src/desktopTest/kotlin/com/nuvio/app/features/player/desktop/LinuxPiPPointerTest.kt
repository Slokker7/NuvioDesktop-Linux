package com.nuvio.app.features.player.desktop

import java.awt.Point
import kotlin.test.*

class LinuxPiPPointerTest {
    private class Task(val action: () -> Unit, var cancelled: Boolean = false)
    private class Fixture {
        var time = 0L
        var toggles = 0
        var exits = 0
        val moves = mutableListOf<Point>()
        val tasks = mutableListOf<Task>()
        val policy = LinuxPiPPointer({ toggles++ }, { exits++ }, { moves.add(it) }, { time }, { delay, action ->
            assertEquals(220, delay)
            val task = Task(action); tasks.add(task)
            val cancel: () -> Unit = { task.cancelled = true }; cancel
        })
        init { policy.setActive(true) }
        fun click() { policy.press(Point(10, 10), Point(100, 100)); policy.release(Point(10, 10)) }
        fun fire(stale: Boolean = false) { tasks.toList().forEach { if (stale || !it.cancelled) it.action() }; tasks.clear() }
    }
    @Test fun firstClickWaitsThenTogglesExactlyOnce() {
        val f = Fixture(); f.click(); assertEquals(0, f.toggles)
        f.fire(); assertEquals(1, f.toggles); f.fire(); assertEquals(1, f.toggles)
    }
    @Test fun secondPressBeforeDeadlineCancelsSingleEvenIfReleaseIsLater() {
        val f = Fixture(); f.click(); f.time = 219
        f.policy.press(Point(10, 10), Point(100, 100)); f.fire(stale = true)
        f.time = 300; f.policy.release(Point(10, 10))
        assertEquals(0, f.toggles); assertEquals(1, f.exits)
    }
    @Test fun doubleClickAndRapidThirdClickOnlyExit() {
        val f = Fixture(); f.click(); f.time = 100; f.click(); f.click(); f.fire(stale = true)
        assertEquals(0, f.toggles); assertEquals(1, f.exits)
    }
    @Test fun secondClickOutsideIntervalIsAnotherSingle() {
        val f = Fixture(); f.click(); f.time = 221; f.fire(); f.click(); f.fire()
        assertEquals(2, f.toggles); assertEquals(0, f.exits)
    }
    @Test fun belowThresholdRemainsClick() {
        val f = Fixture(); f.policy.press(Point(10, 10), Point(100, 100))
        f.policy.motion(Point(15, 10)); f.policy.release(Point(15, 10)); f.fire()
        assertTrue(f.moves.isEmpty()); assertEquals(1, f.toggles)
    }
    @Test fun dragCancelsPendingClickAndReleaseCannotToggle() {
        val f = Fixture(); f.click(); f.time = 100
        f.policy.press(Point(10, 10), Point(100, 100))
        f.policy.motion(Point(16, 10)); f.policy.motion(Point(20, 15)); f.policy.release(Point(20, 15))
        f.fire(stale = true)
        assertEquals(Point(110, 105), f.moves.last()); assertEquals(0, f.toggles); assertEquals(0, f.exits)
    }
    @Test fun physicalThresholdAccountsForScaleAndReleaseMovement() {
        val f = Fixture(); f.policy.press(Point(10, 10), Point(100, 100), 2.0, 2.0)
        f.policy.release(Point(13, 10)); f.fire()
        assertEquals(listOf(Point(103, 100)), f.moves); assertEquals(0, f.toggles)
    }
    @Test fun exitCancelsPendingAndStaleCallback() {
        val f = Fixture(); f.click(); f.policy.setActive(false); f.fire(stale = true)
        assertEquals(0, f.toggles)
    }
    @Test fun disposalCancelsPendingAndCannotReactivate() {
        val f = Fixture(); f.click(); f.policy.close(); f.policy.setActive(true); f.click(); f.fire(stale = true)
        assertEquals(0, f.toggles); assertEquals(0, f.exits)
    }
    @Test fun focusLossCancelsGestureWithoutDeactivatingSession() {
        val f = Fixture(); f.click(); f.policy.reset(); f.fire(stale = true)
        assertEquals(0, f.toggles); f.click(); f.fire(); assertEquals(1, f.toggles)
    }
    @Test fun newSessionRejectsOldCallbacksAndResetsState() {
        val f = Fixture(); f.click(); val old = f.tasks.single()
        f.policy.setActive(false); f.policy.setActive(true); f.click()
        old.action(); assertEquals(0, f.toggles); f.fire(); assertEquals(1, f.toggles)
    }
    @Test fun repeatedActivationDoesNotCancelValidSingle() {
        val f = Fixture(); f.click(); repeat(5) { f.policy.setActive(true) }; f.fire()
        assertEquals(1, f.toggles)
    }
    @Test fun oneListenerPerControllerAndCleanReplacementWithoutAPeer() {
        val host = NativePlayerHost() // A Canvas object only: no addNotify, peer, window or display.
        val first = LinuxPiPSurfaceInput.install(DesktopHostOs.LINUX, host) {}!!
        repeat(5) { host.linuxPiPInteractive = true }
        assertEquals(1, host.mouseListeners.size); assertEquals(1, host.mouseMotionListeners.size)
        first.close(); assertEquals(0, host.mouseListeners.size)
        val second = LinuxPiPSurfaceInput.install(DesktopHostOs.LINUX, host) {}!!
        assertEquals(1, host.mouseListeners.size); second.close(); assertEquals(0, host.mouseMotionListeners.size)
    }
    @Test fun nonLinuxInstallsNothingRegardlessOfFlags() {
        val host = NativePlayerHost()
        DesktopHostOs.entries.filter { it != DesktopHostOs.LINUX }.forEach {
            assertNull(LinuxPiPSurfaceInput.install(it, host) {})
        }
        assertTrue(host.mouseListeners.isEmpty()); assertTrue(host.mouseMotionListeners.isEmpty())
    }
}
