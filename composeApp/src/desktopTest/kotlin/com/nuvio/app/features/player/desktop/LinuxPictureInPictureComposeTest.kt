package com.nuvio.app.features.player.desktop

import com.nuvio.app.LiveDisplayTests
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.launchApplication
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.event.ComponentEvent
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxPictureInPictureComposeTest {
    @org.junit.Before
    fun requireLiveDisplay() = LiveDisplayTests.assumeEnabled()

    @Test fun actualComposeResizeAndStateWritebackConvergeThroughFullscreenPiPAndRestore() = runBlocking {
        org.junit.Assume.assumeTrue(DesktopHostOs.current == DesktopHostOs.LINUX &&
            System.getProperty("nuvio.linux.nativeSmokeTest") == "true" && !GraphicsEnvironment.isHeadless())
        val state = WindowState(size = DpSize(800.dp, 600.dp), position = WindowPosition(40.dp, 40.dp))
        val host = NativePlayerHost()
        val source = Files.createTempFile("nuvio-compose-pip-geometry-", ".y4m")
        val replies = LinkedBlockingQueue<Int>()
        var handle = 0L
        val reference = AtomicReference<ComposeWindow>()
        val failure = AtomicReference<Throwable>()
        val epoch = mutableIntStateOf(0)
        val committedEpoch = AtomicInteger(-1)
        val scope = CoroutineScope(Dispatchers.Swing + SupervisorJob() + CoroutineExceptionHandler { _, error -> failure.set(error) })
        val application = scope.launchApplication {
            Window(onCloseRequest = {}, state = state, title = "Nuvio Linux PiP transition regression") {
                val currentEpoch = epoch.intValue
                SideEffect { committedEpoch.set(currentEpoch) }
                DisposableEffect(window) {
                    reference.set(window)
                    onDispose { reference.set(null) }
                }
                Box(Modifier.fillMaxSize()) { SwingPanel(factory = { host }, modifier = Modifier.fillMaxSize()) }
            }
        }
        var geometryOwned = false
        var transition: LinuxPictureInPictureTransition? = null
        suspend fun await(condition: () -> Boolean) {
            try {
                withTimeout(10_000) {
                    while (true) {
                        failure.get()?.let { throw it }
                        var ready = false
                        SwingUtilities.invokeAndWait { ready = condition() }
                        if (ready) break
                        delay(16)
                    }
                }
            } catch (error: TimeoutCancellationException) {
                SwingUtilities.invokeAndWait {
                    System.err.println("Compose PiP regression timed out: placement=${state.placement}")
                }
                throw error
            }
        }
        try {
            await { reference.get()?.isShowing == true && host.isShowing && host.width > 0 }
            val owner = requireNotNull(reference.get())
            val adapter = LinuxPictureInPictureAwtWindow(owner, state)
            NativePlayerBridge.ensureNativeLibraryLoaded()
            Files.newOutputStream(source).use { output ->
                output.write("YUV4MPEG2 W32 H18 F30:1 Ip A1:1 C420jpeg\n".toByteArray())
                repeat(90) { output.write("FRAME\n".toByteArray()); output.write(ByteArray(32 * 18 * 3 / 2) { 128.toByte() }) }
            }
            var drawable = 0L
            var ownerDrawable = 0L
            SwingUtilities.invokeAndWait {
                drawable = LinuxAwtViewResolver.resolveNativeViewPointer(host)
                ownerDrawable = LinuxAwtViewResolver.resolveNativeViewPointer(owner)
            }
            handle = NativePlayerBridge.create(drawable, source.toString(), null, emptyArray(), false, 0, 0.0,
                NativePlayerBridge.controlsPageUrl, false, false, false, null, arrayOf("ao=null", "loop-file=inf"),
                NativePlayerEventSink { type, value -> if (type == "composeGeometryProbe") replies.add(value.toInt()) })
            LinuxPlayerControlsBridge.setWindowFocused(handle, true)
            NativePlayerBridge.runJavaScript(handle,
                "window.composeGeometryMarker = 42; window.webkit.messageHandlers.player.postMessage({type:'composeGeometryProbe',value:42});")
            assertEquals(42, replies.poll(15, TimeUnit.SECONDS))
            var holdRelease = true
            val boundAssignments = mutableListOf<Rectangle>()
            val delayedWindow = object : LinuxPictureInPictureWindow by adapter {
                override var bounds: Rectangle
                    get() = adapter.bounds
                    set(value) { boundAssignments.add(Rectangle(value)); adapter.bounds = value }
                override fun placeLive(placement: WindowPlacement) {
                    if (placement != WindowPlacement.Floating || !holdRelease) adapter.placeLive(placement)
                }
            }
            val pip = Rectangle(40, 40, 480, 270)
            fun outer() = linuxPiPOuterBounds(pip, adapter.contentInsets, adapter.workArea(pip))
            fun compactReady() = host.linuxPiPInteractive && owner.size == outer().size && state.placement == WindowPlacement.Floating &&
                host.width == 480 && host.height == 270 &&
                state.size == DpSize(owner.width.dp, owner.height.dp) && state.position == WindowPosition(owner.x.dp, owner.y.dp)
            fun fullscreenReady() = owner.placement == WindowPlacement.Fullscreen && adapter.ownsFullscreen &&
                state.placement == WindowPlacement.Fullscreen && owner.bounds == owner.graphicsConfiguration.bounds &&
                adapter.geometrySynchronized && host.width == owner.width - owner.insets.left - owner.insets.right &&
                host.height == owner.height - owner.insets.top - owner.insets.bottom &&
                !LinuxWindowChromeNative.isFloating(ownerDrawable, 0, 0, 0, 0, 0, 0, false)
            SwingUtilities.invokeAndWait {
                state.placement = WindowPlacement.Fullscreen
                adapter.placeLive(WindowPlacement.Fullscreen)
            }
            await { fullscreenReady() }
            SwingUtilities.invokeAndWait {
                val coordinator = LinuxPictureInPictureTransition(delayedWindow, { pip }, {}, {}, { geometryOwned = it },
                    { failure.compareAndSet(null, AssertionError("PiP readiness unexpectedly failed")) })
                transition = coordinator
                val resizeCallbacks = owner.componentListeners.filter { it.javaClass.name.startsWith("androidx.compose.ui.awt.SwingWindow_desktopKt") }
                val stateCallbacks = owner.windowStateListeners.filter { it.javaClass.name.startsWith("androidx.compose.ui.awt.SwingWindow_desktopKt") }
                assertTrue(resizeCallbacks.isNotEmpty(), "Actual Compose resize writeback listener missing")
                assertTrue(stateCallbacks.isNotEmpty(), "Actual Compose state writeback listener missing")
                coordinator.setActive(true)
                assertEquals(WindowPlacement.Floating, state.placement)
                assertEquals(WindowPlacement.Fullscreen, owner.placement)
                assertTrue(boundAssignments.isEmpty(), "PiP must not assign bounds before live fullscreen release")
                resizeCallbacks.forEach { it.componentResized(ComponentEvent(owner, ComponentEvent.COMPONENT_RESIZED)) }
                assertEquals(WindowPlacement.Fullscreen, state.placement, "Pre-release callback must reproduce the reported race")
                holdRelease = false
                adapter.placeLive(WindowPlacement.Floating)
            }
            // The WM may adjust location for frame extents/work-area constraints. The coordinator
            // must request the exact rect once; compact size and actual/state placement must converge.
            await { compactReady() }
            SwingUtilities.invokeAndWait {
                assertEquals(outer().size, boundAssignments.last().size)
                assertEquals(outer().size, owner.size)
                assertEquals(drawable, LinuxAwtViewResolver.resolveNativeViewPointer(host))
                val resizeCallbacks = owner.componentListeners.filter { it.javaClass.name.startsWith("androidx.compose.ui.awt.SwingWindow_desktopKt") }
                val stateCallbacks = owner.windowStateListeners.filter { it.javaClass.name.startsWith("androidx.compose.ui.awt.SwingWindow_desktopKt") }
                resizeCallbacks.forEach { it.componentResized(ComponentEvent(owner, ComponentEvent.COMPONENT_RESIZED)) }
                stateCallbacks.forEach { it.windowStateChanged(WindowEvent(owner, WindowEvent.WINDOW_STATE_CHANGED, Frame.NORMAL, Frame.NORMAL)) }
                assertEquals(WindowPlacement.Floating, state.placement)
                assertFalse(adapter.ownsFullscreen)
                assertEquals(DpSize(owner.width.dp, owner.height.dp), state.size)
                epoch.intValue++
            }
            await { committedEpoch.get() == 1 && compactReady() }
            repeat(3) {
                SwingUtilities.invokeAndWait { requireNotNull(transition).setActive(false); epoch.intValue++ }
                await { fullscreenReady() && !geometryOwned }
                NativePlayerBridge.runJavaScript(handle,
                    "window.webkit.messageHandlers.player.postMessage({type:'composeGeometryProbe',value:window.composeGeometryMarker || -1});")
                assertEquals(42, replies.poll(10, TimeUnit.SECONDS), "PiP replaced or lost the WebView")
                SwingUtilities.invokeAndWait { requireNotNull(transition).setActive(true) }
                await { compactReady() }
                SwingUtilities.invokeAndWait {
                    owner.componentListeners.filter { it.javaClass.name.startsWith("androidx.compose.ui.awt.SwingWindow_desktopKt") }
                        .forEach { it.componentResized(ComponentEvent(owner, ComponentEvent.COMPONENT_RESIZED)) }
                    owner.windowStateListeners.filter { it.javaClass.name.startsWith("androidx.compose.ui.awt.SwingWindow_desktopKt") }
                        .forEach { it.windowStateChanged(WindowEvent(owner, WindowEvent.WINDOW_STATE_CHANGED, Frame.NORMAL, Frame.NORMAL)) }
                    assertEquals(WindowPlacement.Floating, state.placement)
                    assertEquals(outer().size, owner.size)
                    assertEquals(drawable, LinuxAwtViewResolver.resolveNativeViewPointer(host))
                    epoch.intValue++
                }
                await { committedEpoch.get() == epoch.intValue && compactReady() &&
                    owner.placement == WindowPlacement.Floating && state.placement == WindowPlacement.Floating && !adapter.ownsFullscreen }
                assertTrue(NativePlayerBridge.isPaused(handle))
            }
        } finally {
            SwingUtilities.invokeAndWait { transition?.close() }
            if (handle != 0L) NativePlayerBridge.dispose(handle)
            application.cancelAndJoin()
            scope.cancel()
            Files.deleteIfExists(source)
        }
    }
}
