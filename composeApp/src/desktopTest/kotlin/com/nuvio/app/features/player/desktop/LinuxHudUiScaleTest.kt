package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerControlsState
import com.nuvio.app.features.player.PlayerControlsAction
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsStorage
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.event.WindowEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Uses the shipped HUD and real WebKit viewport, without native diagnostic hooks. */
class LinuxHudUiScaleTest {
    private fun enabled(): Boolean {
        val enabled = DesktopHostOs.current == DesktopHostOs.LINUX &&
            System.getProperty("nuvio.linux.nativeSmokeTest") == "true"
        if (enabled) assertTrue(!GraphicsEnvironment.isHeadless(), "UI-scale tests require X11/XWayland")
        return enabled
    }

    @Test
    fun realHudUsesAbsolutePageZoomAndClampsNativeMessages() {
        if (!enabled()) return
        Fixture().use { f ->
            f.open()
            for (percent in listOf(0, 30, -30, 50, -50, 0, 30, 30)) {
                f.controls(percent)
                f.assertZoom(percent)
                assertEquals(42.0, f.probe("window.scaleLifetimeMarker"), "HUD was recreated")
            }
            for ((raw, clamped) in listOf(999 to 50, -999 to -50, 0 to 0)) {
                f.script("window.webkit.messageHandlers.player.postMessage({type:'setControlsUiScalePercent',value:$raw})")
                f.assertZoom(clamped)
            }
            f.script("window.webkit.messageHandlers.player.postMessage({type:'setControlsUiScalePercent',value:NaN})")
            f.assertZoom(0)
        }
    }

    @Test
    fun negativeScaleShrinksPlayerControlsAsWellAsMenusInEveryLayout() {
        if (!enabled()) return
        Fixture().use { f ->
            f.open()
            SwingUtilities.invokeAndWait {
                val screen = f.frame.graphicsConfiguration.bounds
                f.frame.setSize(screen.width, screen.height - 80)
                f.frame.validate()
            }
            f.assertViewportMatchesHost()
            val menu = "contextMenu.getBoundingClientRect().width * devicePixelRatio"
            for (layout in listOf("Standard", "Legacy", "Minimal", "Ultra", "Official")) {
                // Legacy and Ultra intentionally hide the transport cluster upstream.
                val selector = when (layout) {
                    "Legacy" -> "[data-command=audio] svg"
                    "Ultra" -> "#actionOverflowButton .ultra-gear-glyph"
                    else -> "#toggle svg"
                }
                val glyph = "document.querySelector('$selector').getBoundingClientRect().width * devicePixelRatio"
                f.controls(0, layout = layout)
                f.assertZoom(0)
                f.script("window.scaleLayoutSettled=0;setTimeout(()=>window.scaleLayoutSettled=1,350)")
                f.awaitProbe("window.scaleLayoutSettled", 1.0)
                f.script("openContextMenu({preventDefault(){},stopPropagation(){},clientX:20,clientY:20})")
                val baselineGlyph = f.probe(glyph)
                val baselineMenu = f.probe(menu)
                assertTrue(baselineGlyph > 0 && baselineMenu > 0, "$layout must expose real geometry")
                for (percent in listOf(-50, -30, 0, 30, 50, 0)) {
                    f.controls(percent, layout = layout)
                    f.assertZoom(percent)
                    // Wait for resize-driven responsive layout, not just the host zoom change.
                    f.script("window.scaleLayoutSettled=0;setTimeout(()=>window.scaleLayoutSettled=1,350)")
                    f.awaitProbe("window.scaleLayoutSettled", 1.0)
                    val ratio = f.probe(glyph) / baselineGlyph
                    val menuRatio = f.probe(menu) / baselineMenu
                    println("HUD geometry: layout=$layout percent=$percent glyphRatio=$ratio menuRatio=$menuRatio " +
                        "viewport=${f.probe("innerWidth")}x${f.probe("innerHeight")} " +
                        "cssScale=${f.probe("Number(getComputedStyle(document.documentElement).getPropertyValue('--user-scale'))")}")
                    assertEquals(1 + percent / 100.0, ratio, 0.035,
                        "$layout: responsive layout must not cancel the requested user scale")
                    if (percent < 0) assertTrue(menuRatio < 0.95, "$layout: context menu must also shrink")
                    assertEquals(42.0, f.probe("window.scaleLifetimeMarker"))
                }
                f.script("closeContextMenu()")
            }
        }
    }

    @Test
    fun iconMultiplierRemainsIndependentOfWholePageZoom() {
        if (!enabled()) return
        Fixture().use { f ->
            f.open()
            for ((ui, icon) in listOf(0 to 0, 0 to 30, 30 to 0, -30 to 0, -30 to 30, 30 to 30)) {
                f.controls(ui, icon)
                f.assertZoom(ui)
                assertEquals(1 + icon / 100.0,
                    f.probe("Number(getComputedStyle(document.documentElement).getPropertyValue('--control-icon-scale'))"),
                    0.001, "Icon multiplier at UI=$ui icon=$icon")
                // CSS geometry is unaffected by native page zoom; icon CSS changes only the row.
                val header = f.probe("document.getElementById('playerClockTime').getBoundingClientRect().height")
                f.controls(ui, 0)
                f.assertZoom(ui)
                assertEquals(header, f.probe("document.getElementById('playerClockTime').getBoundingClientRect().height"),
                    0.01, "Icon scale changed header geometry")
                val normalIcon = f.probe("document.querySelector('#toggle svg').getBoundingClientRect().width")
                f.controls(ui, 30)
                f.assertZoom(ui)
                assertEquals(1.3, f.probe("document.querySelector('#toggle svg').getBoundingClientRect().width") / normalIcon,
                    0.03, "Icon scale did not independently resize the play glyph")
            }
        }
    }

    @Test
    fun zoomSurvivesGeometryAndFocusWithoutReplacingWebView() {
        if (!enabled()) return
        Fixture().use { f ->
            f.open()
            for (percent in listOf(-50, 0, 50)) {
                f.controls(percent)
                for ((width, height) in listOf(960 to 540, 1280 to 720, 800 to 600, 960 to 540)) {
                    SwingUtilities.invokeAndWait {
                        val screen = f.frame.graphicsConfiguration.bounds
                        f.frame.setSize(width.coerceAtMost(screen.width), height.coerceAtMost(screen.height - 80))
                        f.frame.validate()
                    }
                    f.assertZoom(percent)
                    f.assertViewportMatchesHost()
                    assertEquals(42.0, f.probe("window.scaleLifetimeMarker"))
                }
                f.focus(false)
                f.awaitProbe("document.hidden ? 1 : 0", 1.0)
                f.focus(true)
                f.awaitProbe("document.hidden ? 1 : 0", 0.0)
                f.assertZoom(percent)
                assertEquals(42.0, f.probe("window.scaleLifetimeMarker"))
            }
        }
    }

    @Test
    fun persistedScaleReappliesAcrossSourcesBackAndNewController() {
        if (!enabled()) return
        val settings = PlayerSettingsRepository
        settings.ensureLoaded()
        val previous = settings.uiState.value
        try {
            for (saved in listOf(-30, 30)) {
                settings.setDesktopUiScalePercent(saved)
                assertEquals(saved, PlayerSettingsStorage.loadDesktopUiScalePercent())
                Fixture().use { f ->
                    repeat(3) { source ->
                        f.open(source)
                        f.assertZoom(saved)
                        for (percent in listOf(0, 30, -30, 0, 30)) {
                            settings.setDesktopUiScalePercent(percent)
                            f.controls(settings.uiState.value.desktopUiScalePercent)
                            f.assertZoom(percent)
                        }
                        f.back()
                        settings.setDesktopUiScalePercent(saved)
                    }
                }
            }
        } finally {
            settings.setDesktopUiScalePercent(previous.desktopUiScalePercent)
            settings.setDesktopControlIconScalePercent(previous.desktopControlIconScalePercent)
        }
    }

    @Test
    fun queuedScaleUpdatesAreSafeDuringDisposalAndReplacement() {
        if (!enabled()) return
        Fixture().use { f ->
            repeat(4) {
                f.open(it)
                f.controls(30)
                f.assertZoom(30)
                f.script("setTimeout(() => window.webkit.messageHandlers.player.postMessage({type:'setControlsUiScalePercent',value:50}), 50)")
                f.controls(-30)
                f.closePlayer()
            }
            f.open()
            f.controls(-50)
            f.assertZoom(-50)
        }
    }

    private class Fixture : AutoCloseable {
        private val directory = Files.createTempDirectory("nuvio-linux-hud-scale-")
        val host = NativePlayerHost()
        lateinit var frame: Frame
        private var controller: NativePlayerController? = null
        private val replies = LinkedBlockingQueue<Double>()
        private val backs = LinkedBlockingQueue<Unit>()
        private val handleField = NativePlayerController::class.java.getDeclaredField("handle").apply { isAccessible = true }
        private val handle: Long get() = handleField.getLong(requireNotNull(controller))

        init {
            // Audio-only local source avoids involving GPU/media policy in a WebKit regression.
            val pcmBytes = 8000 * 2 * 10
            val wav = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray())
            wav.putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            wav.put("data".toByteArray()).putInt(pcmBytes)
            repeat(3) { Files.write(directory.resolve("source$it.wav"), wav.array()) }
            SwingUtilities.invokeAndWait {
                frame = Frame("Nuvio Linux HUD UI scale test").apply {
                    isAutoRequestFocus = false
                    focusableWindowState = false
                    add(host)
                    setSize(960, 540)
                    isVisible = true
                }
            }
        }

        fun open(source: Int = 0) {
            SwingUtilities.invokeAndWait {
                val next = NativePlayerController(host)
                controller = next
                next.setControlCallbacks({ action ->
                    if (action == PlayerControlsAction.Back) {
                        next.dispose()
                        controller = null
                        backs.offer(Unit)
                        true
                    } else false
                }, { type, value ->
                    if (type == "hudScaleProbe") replies.offer(value)
                    false
                }, { false }, { false })
                // Queue the saved state before attach, just as the shared player does.
                next.updateControls(PlayerControlsState(uiScalePercent = PlayerSettingsRepository.uiState.value.desktopUiScalePercent))
                next.attach(sourceUrl = directory.resolve("source${source % 3}.wav").toString(),
                    sourceAudioUrl = null, sourceHeaders = emptyMap(), playWhenReady = false,
                    initialPositionMs = 0L, nvidiaRtxSuperResolutionEnabled = false,
                    nvidiaRtxHdrEnabled = false, onError = { error("Attach failed: $it") }, enableUserMpvOptions = false)
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (handle == 0L) {
                assertTrue(System.nanoTime() < deadline, "Controller did not attach")
                Thread.sleep(20)
            }
            focus(true)
            script("window.scaleLifetimeMarker = 42")
            assertEquals(42.0, probe("window.scaleLifetimeMarker"))
        }

        fun controls(ui: Int, icon: Int = 0, layout: String = "Standard") = SwingUtilities.invokeAndWait {
            controller!!.updateControls(PlayerControlsState(title = "HUD scale regression", uiScalePercent = ui,
                controlIconScalePercent = icon, controlsVisible = true, seekThumbnailsEnabled = false,
                legacyHudEnabled = layout == "Legacy", minimalHudEnabled = layout == "Minimal",
                ultraHudEnabled = layout == "Ultra", officialHudEnabled = layout == "Official"))
        }

        fun script(js: String) = NativePlayerBridge.runJavaScript(handle, js)

        fun probe(expression: String): Double {
            script("window.webkit.messageHandlers.player.postMessage({type:'hudScaleProbe',value:Number($expression)})")
            return requireNotNull(replies.poll(15, TimeUnit.SECONDS)) { "No real WebKit response for $expression" }
        }

        fun awaitProbe(expression: String, expected: Double) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var actual: Double
            do {
                actual = probe(expression)
                if (kotlin.math.abs(expected - actual) < 0.01) return
                Thread.sleep(30)
            } while (System.nanoTime() < deadline)
            assertEquals(expected, actual, 0.01, expression)
        }

        fun assertZoom(percent: Int) = awaitProbe("devicePixelRatio", 2.0 * (1 + percent / 100.0))

        fun assertViewportMatchesHost() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            do {
                var physicalWidth = 0.0
                // WM configure events can clamp a requested size after setSize returns.
                SwingUtilities.invokeAndWait {
                    physicalWidth = host.width * host.graphicsConfiguration.defaultTransform.scaleX
                }
                if (kotlin.math.abs(probe("innerWidth * devicePixelRatio") - physicalWidth) < 4) return
                Thread.sleep(30)
            } while (System.nanoTime() < deadline)
            error("WebKit viewport did not follow the actual Canvas bounds")
        }

        fun focus(focused: Boolean) = SwingUtilities.invokeAndWait {
            val event = WindowEvent(frame, if (focused) WindowEvent.WINDOW_GAINED_FOCUS else WindowEvent.WINDOW_LOST_FOCUS)
            frame.windowFocusListeners.forEach { if (focused) it.windowGainedFocus(event) else it.windowLostFocus(event) }
        }

        fun closePlayer() {
            SwingUtilities.invokeAndWait { controller?.dispose(); controller = null }
            replies.clear()
        }

        fun back() {
            script("send('back', 0)")
            assertTrue(backs.poll(10, TimeUnit.SECONDS) != null, "Back did not dispose the controller")
            replies.clear()
        }

        override fun close() {
            closePlayer()
            SwingUtilities.invokeAndWait { frame.dispose() }
            directory.toFile().deleteRecursively()
        }
    }
}
