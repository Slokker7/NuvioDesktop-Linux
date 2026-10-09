package com.nuvio.app.features.screensaver

import com.nuvio.app.features.player.desktop.LinuxPlayerWindowFocus
import com.nuvio.app.features.player.desktop.NativePlayerHost
import javax.swing.JFrame
import java.awt.Canvas
import java.awt.Frame
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxScreensaverTest {
    private class Fixture {
        var now = 0L
        var nativeIdle: Long? = null
        var visible = false
        var opacity = 0f
        var disposed = 0
        var closed = 0
        val settings = ScreensaverSettings(enabled = true, dimDelayMinutes = 1,
            playbackDimDelayMinutes = 2, activeDuringPlayback = true, dimPercent = 65)
        val controller = LinuxScreensaverController(object : LinuxIdleSource {
            override fun idleMs() = nativeIdle
            override fun close() { closed++ }
        }, { visible = true; opacity = it }, { visible = false }, { disposed++; visible = false }, { now })
        fun tick(player: Boolean = false, showing: Boolean = true, config: ScreensaverSettings = settings) =
            controller.tick(config, player, showing)
    }

    @Test fun browsingBoundaryAndOpacity() = with(Fixture()) {
        now = 59_999; tick(); assertFalse(visible)
        now = 60_000; tick(); assertTrue(visible); assertEquals(0.65f, opacity)
    }
    @Test fun repeatedActivityDismissesAndResetsFallbackClock() = with(Fixture()) {
        now = 60_000; tick(); assertTrue(visible)
        controller.activity(); assertFalse(visible)
        now = 119_999; tick(); assertFalse(visible)
        controller.activity()
        now = 179_998; tick(); assertFalse(visible)
        now = 179_999; tick(); assertTrue(visible)
    }
    @Test fun playerUsesPlaybackTimeoutEvenWhenPaused() = with(Fixture()) {
        now = 60_000; tick(player = true); assertFalse(visible)
        now = 120_000; tick(player = true); assertTrue(visible)
    }
    @Test fun disabledPlaybackDimmingSuppressesMountedPlayer() = with(Fixture()) {
        now = 900_000; tick(player = true, config = settings.copy(activeDuringPlayback = false))
        assertFalse(visible)
        tick(player = false, config = settings.copy(activeDuringPlayback = false)); assertTrue(visible)
    }
    @Test fun timeoutAndMountTransitionsUseCurrentIdleWithoutRestart() = with(Fixture()) {
        now = 60_000; tick(); assertTrue(visible)
        tick(player = true); assertFalse(visible)
        tick(player = false); assertTrue(visible)
        tick(config = settings.copy(dimDelayMinutes = 2)); assertFalse(visible)
        now = 120_000; tick(config = settings.copy(dimDelayMinutes = 2)); assertTrue(visible)
    }
    @Test fun nativeHudActivityDismissesAndLocalActivityBeatsStaleNativeSample() = with(Fixture()) {
        now = 120_000; nativeIdle = 120_000; tick(); assertTrue(visible)
        nativeIdle = 0; tick(); assertFalse(visible)
        nativeIdle = 120_000; controller.activity(); tick(); assertFalse(visible)
    }
    @Test fun missingOrInvalidBackendFallsBack() = with(Fixture()) {
        now = 60_000; nativeIdle = -1; tick(); assertTrue(visible)
        controller.activity(); nativeIdle = null; tick(); assertFalse(visible)
        now = 120_000; tick(); assertTrue(visible)
    }
    @Test fun hiddenMinimizedAndDisabledOwnersDoNotShade() = with(Fixture()) {
        now = 60_000; tick(); assertTrue(visible)
        tick(showing = false); assertFalse(visible)
        tick(config = settings.copy(enabled = false)); assertFalse(visible)
    }
    @Test fun shutdownSettingsHaveNoActionOnLinux() = with(Fixture()) {
        now = 24 * 60 * 60_000L
        tick(config = settings.copy(enabled = false, shutdownEnabled = true,
            shutdownDelayMinutes = 1, playbackShutdownDelayMinutes = 1))
        assertFalse(visible); assertEquals(0, disposed)
        tick(config = settings.copy(shutdownEnabled = true)); assertTrue(visible)
    }
    @Test fun disposalClosesBackendAndShadeExactlyOnce() = with(Fixture()) {
        now = 60_000; tick(); assertTrue(visible)
        controller.close(); controller.close(); tick(); controller.activity()
        assertFalse(visible); assertEquals(1, closed); assertEquals(1, disposed)
    }
    @Test fun nativeBackendExceptionFallsBack() {
        var now = 0L
        var visible = false
        val controller = LinuxScreensaverController(LinuxIdleSource { error("disconnected") },
            { visible = true }, { visible = false }, {}, { now })
        now = 60_000
        controller.tick(ScreensaverSettings(enabled = true, dimDelayMinutes = 1), false, true)
        assertTrue(visible)
        controller.close()
    }
    @Test fun meaningfulInputFiltersMapEventsAndTinyMovement() {
        val source = Canvas()
        fun mouse(id: Int, x: Int, y: Int) = MouseEvent(source, id, 0, 0, x, y, x, y, 0, false, 0)
        val resting = Point(100, 100)
        assertFalse(LinuxScreensaver.meaningfulInput(mouse(MouseEvent.MOUSE_ENTERED, 300, 300), resting))
        assertFalse(LinuxScreensaver.meaningfulInput(mouse(MouseEvent.MOUSE_EXITED, 300, 300), resting))
        assertFalse(LinuxScreensaver.meaningfulInput(mouse(MouseEvent.MOUSE_MOVED, 102, 98), resting))
        assertTrue(LinuxScreensaver.meaningfulInput(mouse(MouseEvent.MOUSE_MOVED, 103, 100), resting))
        assertTrue(LinuxScreensaver.meaningfulInput(mouse(MouseEvent.MOUSE_DRAGGED, 100, 97), resting))
        assertTrue(LinuxScreensaver.meaningfulInput(mouse(MouseEvent.MOUSE_PRESSED, 100, 100), resting))
        assertTrue(LinuxScreensaver.meaningfulInput(KeyEvent(source, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_A, 'a'), resting))
        assertTrue(LinuxScreensaver.meaningfulInput(MouseWheelEvent(source, MouseEvent.MOUSE_WHEEL, 0, 0,
            0, 0, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1), resting))
    }
    @Test fun ownedShadeFollowsMoveResizeAndFullscreenBoundsAndDisposesTracker() {
        SwingUtilities.invokeAndWait {
            val owner = Frame()
            val before = owner.componentListeners.size
            val shade = LinuxScreensaver.createShade(owner)
            try {
                for (bounds in listOf(Rectangle(50, 60, 800, 450), Rectangle(200, 150, 1000, 700), Rectangle(0, 0, 1920, 1080))) {
                    owner.bounds = bounds
                    owner.dispatchEvent(ComponentEvent(owner, ComponentEvent.COMPONENT_RESIZED))
                    owner.dispatchEvent(ComponentEvent(owner, ComponentEvent.COMPONENT_MOVED))
                    assertEquals(bounds, shade.bounds)
                }
                assertEquals(java.awt.Window.Type.POPUP, shade.type)
                assertEquals(owner, shade.owner)
                assertFalse(shade.focusableWindowState)
                assertFalse(shade.isAlwaysOnTop)
            } finally {
                shade.dispose(); assertEquals(before, owner.componentListeners.size); owner.dispose()
            }
        }
    }
    @Test fun shadeSuppressesHudAndRestoresOnlyWhileOwnerFocused() {
        SwingUtilities.invokeAndWait {
            var foreground = true
            val owner = object : JFrame() { override fun isFocused() = foreground }
            val host = NativePlayerHost()
            owner.add(host)
            owner.pack()
            val values = mutableListOf<Boolean>()
            val tracker = LinuxPlayerWindowFocus(host) { values.add(it) }
            try {
                assertTrue(tracker.focused)
                LinuxPlayerWindowFocus.setDimmed(owner, true)
                assertFalse(tracker.focused)
                LinuxPlayerWindowFocus.setDimmed(owner, false)
                assertTrue(tracker.focused)
                foreground = false
                LinuxPlayerWindowFocus.setDimmed(owner, true)
                LinuxPlayerWindowFocus.setDimmed(owner, false)
                assertFalse(tracker.focused)
                tracker.close()
                val count = values.size
                LinuxPlayerWindowFocus.setDimmed(owner, true)
                assertEquals(count, values.size)
            } finally { tracker.close(); owner.dispose() }
        }
    }
    @Test fun asyncBackendCloseDuringOpenReleasesOnWorker() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val releases = AtomicInteger()
        val source = LinuxSessionIdleSource(open = {
            entered.countDown(); assertTrue(finish.await(5, TimeUnit.SECONDS))
            object : LinuxIdleSource {
                override fun idleMs() = 42L
                override fun close() { assertTrue(Thread.currentThread().name == "nuvio-idle-probe"); releases.incrementAndGet() }
            }
        })
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        source.close(); source.close(); finish.countDown()
        assertTrue(source.awaitClosed(5_000)); assertEquals(1, releases.get()); assertNull(source.idleMs())
    }
    @Test fun missingNativeLibraryFallsBackWithoutRapidRetries() {
        val attempts = AtomicInteger()
        val attempted = CountDownLatch(1)
        val source = LinuxSessionIdleSource(open = {
            attempts.incrementAndGet(); attempted.countDown(); throw UnsatisfiedLinkError("test missing bridge")
        })
        assertTrue(attempted.await(5, TimeUnit.SECONDS))
        assertNull(source.idleMs()); source.close(); assertTrue(source.awaitClosed(5_000)); assertEquals(1, attempts.get())
    }
}
