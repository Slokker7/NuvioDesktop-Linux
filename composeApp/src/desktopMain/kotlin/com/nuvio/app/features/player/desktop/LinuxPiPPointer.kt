package com.nuvio.app.features.player.desktop

import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.hypot

/** EDT-owned policy; no native player, toolkit, or window access. */
internal class LinuxPiPPointer(
    private val toggle: () -> Unit,
    private val fullscreen: () -> Unit,
    private val move: (Point) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val schedule: (Int, () -> Unit) -> (() -> Unit) = { delay, action ->
        val timer = Timer(delay) { action() }.apply { isRepeats = false; start() }
        val cancel: () -> Unit = { timer.stop() }
        cancel
    },
) : AutoCloseable {
    private var active = false
    private var closed = false
    private var generation = 0L
    private var cancel: (() -> Unit)? = null
    private var pendingAt: Long? = null
    private var down: Point? = null
    private var origin = Point()
    private var dragging = false
    private var second = false
    private var scaleX = 1.0
    private var scaleY = 1.0

    fun setActive(value: Boolean) {
        if (closed || active == value) return
        reset()
        active = value
    }

    fun press(screen: Point, window: Point, sx: Double = 1.0, sy: Double = 1.0) {
        if (!active || closed || down != null) return
        second = pendingAt?.let { now() - it in 0..DelayMs.toLong() } == true
        if (second) cancelSingle()
        down = Point(screen)
        origin = Point(window)
        scaleX = sx; scaleY = sy
        dragging = false
    }

    fun motion(screen: Point) {
        val start = down ?: return
        if (!active || closed) return
        val dx = screen.x - start.x; val dy = screen.y - start.y
        if (!dragging && hypot(dx * scaleX, dy * scaleY) >= ThresholdPixels) {
            dragging = true
            second = false
            cancelSingle()
        }
        if (dragging) move(Point(origin.x + dx, origin.y + dy))
    }

    fun release(screen: Point) {
        if (!active || closed || down == null) return
        motion(screen)
        down = null
        if (dragging) { dragging = false; second = false; return }
        if (second) {
            setActive(false) // Triple clicks and reentrant callbacks cannot arm another action.
            fullscreen()
            return
        }
        cancelSingle()
        pendingAt = now()
        val token = generation
        cancel = schedule(DelayMs) {
            if (closed || !active || token != generation || pendingAt == null) return@schedule
            cancel = null
            pendingAt = null
            toggle()
        }
    }

    fun reset() {
        cancelSingle()
        down = null; dragging = false; second = false
    }
    private fun cancelSingle() {
        ++generation
        cancel?.invoke(); cancel = null; pendingAt = null
    }
    override fun close() { reset(); active = false; closed = true }

    companion object {
        const val DelayMs = 220
        const val ThresholdPixels = 6.0
    }
}

/** One listener per Linux player controller, attached to its existing Canvas only. */
internal class LinuxPiPSurfaceInput(private val host: NativePlayerHost, toggle: () -> Unit) : AutoCloseable {
    companion object {
        fun install(os: DesktopHostOs, host: NativePlayerHost, toggle: () -> Unit): LinuxPiPSurfaceInput? =
            if (os == DesktopHostOs.LINUX) LinuxPiPSurfaceInput(host, toggle) else null
    }
    private fun current() = host.linuxPiPInteractive && host.isDisplayable && desktopPictureInPictureState.value
    private val policy = LinuxPiPPointer(toggle = { if (current()) toggle() },
        fullscreen = { host.onLinuxPiPFullscreen?.invoke() },
        move = { host.onLinuxPiPMove?.invoke(it) })
    private val listener = object : MouseAdapter() {
        override fun mousePressed(event: MouseEvent) {
            if (!current() || event.button != MouseEvent.BUTTON1) return
            val window = SwingUtilities.getWindowAncestor(host) ?: return
            val scale = host.graphicsConfiguration?.defaultTransform
            policy.press(event.locationOnScreen, window.location, scale?.scaleX ?: 1.0, scale?.scaleY ?: 1.0)
            event.consume()
        }
        override fun mouseDragged(event: MouseEvent) {
            if (!current()) return
            policy.motion(event.locationOnScreen)
            event.consume()
        }
        override fun mouseReleased(event: MouseEvent) {
            if (!current() || event.button != MouseEvent.BUTTON1) return
            policy.release(event.locationOnScreen)
            event.consume()
        }
    }
    init {
        host.onLinuxPiPInteractiveChanged = policy::setActive
        host.onLinuxPiPCancelGesture = policy::reset
        host.addMouseListener(listener)
        host.addMouseMotionListener(listener)
        policy.setActive(host.linuxPiPInteractive)
    }
    override fun close() {
        policy.close()
        host.removeMouseListener(listener)
        host.removeMouseMotionListener(listener)
        host.onLinuxPiPInteractiveChanged = null
        host.onLinuxPiPCancelGesture = null
    }
}
