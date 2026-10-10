package com.nuvio.app.features.player.desktop

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Rectangle
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.roundToInt

internal data class DesktopPictureInPictureRestore(
    val bounds: Rectangle,
    val placement: WindowPlacement,
    val extendedState: Int,
    val alwaysOnTop: Boolean,
    val borderlessFullscreen: Boolean,
)

internal interface LinuxPictureInPictureWindow {
    val displayable: Boolean
    val livePlacement: WindowPlacement
    val ownsFullscreen: Boolean
    val geometrySynchronized: Boolean
    val windowManagerFloating: Boolean
    val chromeGeometrySynchronized: Boolean
    var requestedPlacement: WindowPlacement
    var bounds: Rectangle
    var extendedState: Int
    var alwaysOnTop: Boolean
    fun suppressHud(value: Boolean): Boolean
    fun interactive(value: Boolean)
    fun restoredPlacementReady(placement: WindowPlacement): Boolean
    fun placeLive(placement: WindowPlacement)
    fun syncGeometry()
    fun compact(enabled: Boolean)
    fun toFront()
    val contentInsets: Insets
    fun canvasGeometry(): LinuxPiPCanvasGeometry?
    fun workArea(target: Rectangle): Rectangle
    fun beginGeometry()
    fun cancelGeometry()
    fun syncHud(geometry: LinuxPiPCanvasGeometry, remainingNanos: Long): LinuxPiPHudRequest
    fun hudAligned(geometry: LinuxPiPCanvasGeometry): Boolean
    fun observeGeometry(changed: () -> Unit): () -> Unit
}

internal class LinuxPictureInPictureAwtWindow(
    private val window: ComposeWindow,
    private val state: WindowState,
    private val exitToFullscreen: () -> Unit = {},
) : LinuxPictureInPictureWindow {
    private val geometry = LinuxPictureInPictureGeometry(window)
    override fun suppressHud(value: Boolean) = geometry.suppressHud(value)
    override fun interactive(value: Boolean) {
        geometry.host()?.let { host ->
            host.onLinuxPiPMove = { point ->
                if (host.linuxPiPInteractive && desktopPictureInPictureState.value && window.placement == WindowPlacement.Floating) {
                    window.setLocation(point)
                    syncGeometry() // Match AWT before Compose can write back a stale position.
                }
            }
            host.onLinuxPiPFullscreen = exitToFullscreen
            host.linuxPiPInteractive = value
        }
    }
    override fun restoredPlacementReady(placement: WindowPlacement): Boolean {
        if (livePlacement != placement || !geometrySynchronized) return false
        if (placement == WindowPlacement.Fullscreen) {
            val screen = window.graphicsConfiguration.bounds
            return ownsFullscreen && window.bounds == screen
        }
        return !ownsFullscreen
    }
    override val contentInsets get() = geometry.insets
    override fun canvasGeometry() = geometry.sample()
    override fun workArea(target: Rectangle) = geometry.workArea(target)
    override fun beginGeometry() = geometry.begin()
    override fun cancelGeometry() = geometry.cancel()
    override fun syncHud(geometry: LinuxPiPCanvasGeometry, remainingNanos: Long) = this.geometry.syncHud(geometry, remainingNanos)
    override fun hudAligned(geometry: LinuxPiPCanvasGeometry) = this.geometry.hudAligned(geometry)
    override fun observeGeometry(changed: () -> Unit) = geometry.observe(changed)
    override val displayable get() = window.isDisplayable
    override val livePlacement get() = window.placement
    override val ownsFullscreen get() = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .any { it.fullScreenWindow === window }
    override val geometrySynchronized get() = state.size == DpSize(window.width.dp, window.height.dp) &&
        state.position == WindowPosition(window.x.dp, window.y.dp)
    override val windowManagerFloating get() = windowManagerReady(checkFrame = false)
    // Motif is best-effort. WM frame hints can remain stale after a request;
    // native client/Canvas adoption, rather than a frame-property acknowledgement,
    // determines effective content loss and final readiness.
    override val chromeGeometrySynchronized get() = windowManagerReady(checkFrame = false, checkClientSize = false)
    private fun windowManagerReady(checkFrame: Boolean, checkClientSize: Boolean = true) = runCatching {
        val insets = window.insets
        val scale = window.graphicsConfiguration.defaultTransform
        LinuxWindowChromeNative.isFloating(LinuxAwtViewResolver.resolveNativeViewPointer(window),
            if (checkClientSize) ((window.width - insets.left - insets.right) * scale.scaleX).roundToInt() else 0,
            if (checkClientSize) ((window.height - insets.top - insets.bottom) * scale.scaleY).roundToInt() else 0,
            (insets.left * scale.scaleX).roundToInt(), (insets.right * scale.scaleX).roundToInt(),
            (insets.top * scale.scaleY).roundToInt(), (insets.bottom * scale.scaleY).roundToInt(), checkFrame)
    }.getOrDefault(false)
    override var requestedPlacement: WindowPlacement
        get() = state.placement
        set(value) { state.placement = value }
    override var bounds: Rectangle
        get() = Rectangle(window.bounds)
        set(value) { window.bounds = value }
    override var extendedState: Int
        get() = window.extendedState
        set(value) { window.extendedState = value }
    override var alwaysOnTop: Boolean
        get() = window.isAlwaysOnTop
        set(value) { window.isAlwaysOnTop = value }
    override fun placeLive(placement: WindowPlacement) { window.placement = placement }
    override fun syncGeometry() {
        state.size = DpSize(window.width.dp, window.height.dp)
        state.position = WindowPosition(window.x.dp, window.y.dp)
    }
    override fun compact(enabled: Boolean) = applyNativeCompactPlayerWindow(window, enabled)
    override fun toFront() = window.toFront()
}

internal class LinuxPictureInPictureTransition(
    private val window: LinuxPictureInPictureWindow,
    private val pictureInPictureBounds: () -> Rectangle,
    private val rememberBounds: (Rectangle) -> Unit,
    private val fullscreenChanged: (Boolean) -> Unit,
    private val geometryOwned: (Boolean) -> Unit,
    private val rejected: () -> Unit,
    private val isActive: () -> Boolean = { true },
    private val now: () -> Long = System::nanoTime,
    private val schedule: (() -> Unit) -> (() -> Unit) = { action ->
        val timer = Timer(16) { action() }.apply { isRepeats = false; start() }
        val cancel: () -> Unit = { timer.stop() }
        cancel
    },
) : AutoCloseable {
    private var restore: DesktopPictureInPictureRestore? = null
    private var restoreHud: LinuxPiPHudRestore? = null
    private var exitFullscreen = false

    fun requestExitToFullscreen() { exitFullscreen = true }

    private var generation = 0L
    private var cancelCheck: (() -> Unit)? = null
    private var deadline = 0L
    private var checks = 0
    private var chromeRequested = false
    private var settledBounds: Rectangle? = null
    private var complete = false
    private var closed = false
    private var contentTarget: Rectangle? = null
    private var finalCanvas: LinuxPiPCanvasGeometry? = null
    private var completedCanvas: LinuxPiPCanvasGeometry? = null
    private var uninstallGeometry: (() -> Unit)? = null
    private var wasComplete = false
    private var recoveryStopped = false
    private var recoveryCount = 0
    private var recoveryChecks = 0
    private var recoveryMutations = 0

    fun setActive(active: Boolean) {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        if (!active) {
            exit()
            return
        }
        if (restore != null) return
        if (!window.displayable) { rejected(); return }
        restore = DesktopPictureInPictureRestore(Rectangle(window.bounds), window.requestedPlacement,
            window.extendedState, window.alwaysOnTop, false)
        wasComplete = false
        recoveryStopped = false
        recoveryCount = 0
        recoveryChecks = 0
        recoveryMutations = 0
        restoreHud?.close()
        restoreHud = null
        window.interactive(false)
        window.beginGeometry()
        geometryOwned(true)
        val token = ++generation
        deadline = now() + 2_000_000_000L
        checks = 0
        contentTarget = null
        finalCanvas = null
        completedCanvas = null
        uninstallGeometry = window.observeGeometry(::geometryChanged)
        try {
            if (!window.suppressHud(true)) { fail(token, "HUD suppression failed"); return }
            val alreadyFloating = ready()
            fullscreenChanged(false)
            window.requestedPlacement = WindowPlacement.Floating
            window.placeLive(WindowPlacement.Floating)
            window.extendedState = Frame.NORMAL
            awaitFloating(token, accept = alreadyFloating)
        } catch (error: Exception) {
            fail(token, error.toString())
        }
    }

    private fun floating() = window.livePlacement == WindowPlacement.Floating && !window.ownsFullscreen
    // The WM reply may arrive while AWT processes its fullscreen-restoration ConfigureNotify.
    // Read live/state geometry after that round trip, never accept a pre-query geometry sample.
    private fun ready() = (if (chromeRequested) window.chromeGeometrySynchronized else window.windowManagerFloating) &&
        floating() && window.geometrySynchronized

    private fun awaitFloating(token: Long, accept: Boolean = true) {
        check(SwingUtilities.isEventDispatchThread())
        if (closed || token != generation || restore == null) return
        cancelCheck = null
        if (!isActive()) return
        if (!window.displayable) { close(); rejected(); return }
        try {
            if (checks > 0 && now() >= deadline) {
                fail(token, "Floating readiness timed out")
            } else if (accept && ready()) {
                if (!chromeRequested) {
                    window.requestedPlacement = WindowPlacement.Floating
                    chromeRequested = true
                    window.compact(true)
                    val chromeReady = ready()
                    settledBounds = if (chromeReady) Rectangle(window.bounds) else null
                    if (checks > 0 || !chromeReady) {
                        if (++checks >= 128) fail(token, "Floating readiness check budget exhausted")
                        else cancelCheck = schedule { awaitFloating(token) }
                        return
                    }
                }
                if (checks > 0 && settledBounds != window.bounds) {
                    settledBounds = Rectangle(window.bounds)
                    if (++checks >= 128) fail(token, "Floating readiness check budget exhausted")
                    else cancelCheck = schedule { awaitFloating(token) }
                    return
                }
                val canvas = window.canvasGeometry()
                if (canvas == null) {
                    defer(token) { awaitFloating(token) }
                    return
                }
                window.alwaysOnTop = true
                contentTarget = Rectangle(pictureInPictureBounds())
                applyBounds(canvas)
                window.toFront()
                defer(token) { awaitFinalCanvas(token) }
            } else if (++checks >= 128) {
                fail(token, "Floating readiness check budget exhausted")
            } else {
                if (chromeRequested) settledBounds = null
                cancelCheck = schedule { awaitFloating(token) }
            }
        } catch (error: Exception) {
            fail(token, error.toString())
        }
    }

    private fun applyBounds(canvas: LinuxPiPCanvasGeometry): Boolean {
        val target = requireNotNull(contentTarget)
        val outer = linuxPiPOuterBounds(target, canvas.loss, window.workArea(target))
        if (window.bounds != outer) {
            if (wasComplete && ++recoveryMutations > 4) { stopRecovery("outer mutation budget exhausted"); return false }
            window.bounds = outer
        }
        window.syncGeometry()
        finalCanvas = null
        return true
    }

    // Shares the entry's original 2 s / 128-check budget with floating/chrome settlement.
    private fun defer(token: Long, action: () -> Unit) {
        if (wasComplete && ++recoveryChecks >= 128) stopRecovery("session recovery check budget exhausted")
        else if (++checks >= 128 || now() >= deadline) fail(token, "PiP geometry settlement budget exhausted")
        else cancelCheck = schedule(action)
    }

    private fun awaitFinalCanvas(token: Long) {
        check(SwingUtilities.isEventDispatchThread())
        if (closed || token != generation || restore == null) return
        cancelCheck = null
        if (!isActive()) return // Exit handler owns restoration; never run a stale HUD sync.
        if (!window.displayable) { close(); rejected(); return }
        try {
            if (now() >= deadline) { fail(token, "Final Canvas settlement timed out"); return }
            val canvas = if (window.requestedPlacement == WindowPlacement.Floating && ready()) window.canvasGeometry() else null
            if (!isActive()) return
            if (now() >= deadline) { fail(token, "Canvas observation exceeded deadline"); return }
            if (canvas == null) {
                finalCanvas = null
                defer(token) { awaitFinalCanvas(token) }
                return
            }
            val target = requireNotNull(contentTarget)
            val desired = linuxPiPOuterBounds(target, canvas.loss, window.workArea(target))
            if (window.bounds.size != desired.size || !window.workArea(target).contains(window.bounds)) {
                // Debounce changing loss/layout across separate turns before another outer write.
                if (finalCanvas != canvas) {
                    finalCanvas = canvas
                } else if (!applyBounds(canvas)) return
                defer(token) { awaitFinalCanvas(token) }
                return
            }
            val expectedWidth = desired.width - canvas.loss.left - canvas.loss.right
            val expectedHeight = desired.height - canvas.loss.top - canvas.loss.bottom
            if (canvas.component.width != expectedWidth || canvas.component.height != expectedHeight || finalCanvas != canvas) {
                finalCanvas = canvas
                defer(token) { awaitFinalCanvas(token) }
                return
            }
            // The HUD remains hidden. Entry settles only the existing playable Canvas;
            // WebKit/GTK geometry is synchronized once after restoration instead.
            if (!ready() || window.canvasGeometry() != canvas) {
                finalCanvas = null
                defer(token) { awaitFinalCanvas(token) }
                return
            }
            completedCanvas = canvas
            complete = true
            window.interactive(true)
            wasComplete = true
        } catch (error: Exception) { fail(token, error.toString()) }
    }

    private fun geometryChanged() {
        if (closed || restore == null || recoveryStopped || !complete || !isActive()) return
        val previous = completedCanvas ?: return
        // Ordinary user move/resize remains native. Only changing effective chrome
        // restarts compensation, using the last playable size and outer bottom/right.
        if (window.contentInsets == previous.loss) {
            val outer = Rectangle(window.bounds)
            if (outer.size == previous.outer.size) {
                // Drag is position-only: remember AWT's position without a synchronous GTK/X11
                // query on every mouse move. Validated playable dimensions remain unchanged.
                completedCanvas = previous.copy(outer = outer)
            } else window.canvasGeometry()?.let { completedCanvas = it }
            return
        }
        if (++recoveryCount > 4) { stopRecovery("late inset recovery budget exhausted"); return }
        window.cancelGeometry()
        window.beginGeometry()
        contentTarget = linuxPiPContentTarget(previous)
        complete = false
        window.interactive(false)
        finalCanvas = null
        checks = 0
        deadline = now() + 2_000_000_000L
        val token = ++generation
        defer(token) { awaitFinalCanvas(token) }
    }

    private fun stopRecovery(reason: String) {
        // Keep playback and the current usable PiP window. Do not roll back or keep
        // compensating an oscillating WM. Only a NEW PiP entry resets these budgets.
        recoveryStopped = true
        window.interactive(true)
        complete = false
        ++generation
        cancelCheck?.invoke()
        cancelCheck = null
        window.cancelGeometry()
        System.err.println("Linux PiP recovery stopped: $reason; retaining current window")
    }

    private fun fail(token: Long, reason: String) {
        if (closed || token != generation) return
        System.err.println("Linux PiP transition failed: $reason")
        if (wasComplete) { stopRecovery(reason); return }
        try { exit() } finally { rejected() }
    }

    private fun exit() {
        window.interactive(false)
        ++generation
        cancelCheck?.invoke()
        cancelCheck = null
        val original = restore ?: return
        val saved = if (exitFullscreen) original.copy(placement = WindowPlacement.Fullscreen, extendedState = Frame.NORMAL) else original
        exitFullscreen = false
        window.cancelGeometry()
        restore = null
        uninstallGeometry?.invoke()
        uninstallGeometry = null
        try {
            if (!window.displayable) return
            if (complete) completedCanvas?.let { rememberBounds(linuxPiPContentTarget(it)) }
            if (chromeRequested) window.compact(false)
            if (chromeRequested) window.alwaysOnTop = saved.alwaysOnTop
            if (chromeRequested) {
                window.requestedPlacement = WindowPlacement.Floating
                window.placeLive(WindowPlacement.Floating)
                window.extendedState = Frame.NORMAL
                if (floating()) {
                    window.bounds = Rectangle(saved.bounds)
                    window.syncGeometry()
                }
            }
            window.extendedState = saved.extendedState
            window.requestedPlacement = saved.placement
            window.placeLive(saved.placement)
            fullscreenChanged(saved.placement == WindowPlacement.Fullscreen)
            window.toFront()
        } finally {
            chromeRequested = false
            settledBounds = null
            complete = false
            contentTarget = null
            finalCanvas = null
            completedCanvas = null
            if (window.displayable) {
                window.beginGeometry()
                restoreHud = LinuxPiPHudRestore(window, saved.placement, now, schedule,
                    finished = { geometryOwned(false) },
                    failed = { reason -> geometryOwned(false); System.err.println("Linux PiP HUD restore: $reason; HUD remains suppressed") })
                restoreHud?.start()
            } else geometryOwned(false)
        }
    }

    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        closed = true
        window.interactive(false)
        restoreHud?.close()
        restoreHud = null
        window.cancelGeometry()
        ++generation
        cancelCheck?.invoke()
        cancelCheck = null
        restore = null
        uninstallGeometry?.invoke()
        uninstallGeometry = null
        geometryOwned(false)
    }
}
