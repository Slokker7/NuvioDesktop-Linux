package com.nuvio.app.features.player.desktop

import androidx.compose.ui.awt.ComposeWindow
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import kotlin.math.min
import kotlin.math.roundToInt

internal data class LinuxPiPCanvasGeometry(
    val drawable: Long,
    val component: Rectangle,
    val root: Rectangle,
    val outer: Rectangle,
    val loss: Insets,
)

/** Target width/height are content units; its right/bottom anchor the outer window. */
internal fun linuxPiPOuterBounds(target: Rectangle, loss: Insets, work: Rectangle): Rectangle {
    val horizontal = loss.left + loss.right
    val vertical = loss.top + loss.bottom
    val ratio = min(1.0, min((work.width - horizontal).coerceAtLeast(1).toDouble() / target.width,
        (work.height - vertical).coerceAtLeast(1).toDouble() / target.height))
    val width = (target.width * ratio).roundToInt().coerceAtLeast(1) + horizontal
    val height = (target.height * ratio).roundToInt().coerceAtLeast(1) + vertical
    return Rectangle((target.x + target.width - width).coerceIn(work.x, maxOf(work.x, work.x + work.width - width)),
        (target.y + target.height - height).coerceIn(work.y, maxOf(work.y, work.y + work.height - height)), width, height)
}

internal fun linuxPiPContentTarget(geometry: LinuxPiPCanvasGeometry): Rectangle = Rectangle(
    geometry.outer.x + geometry.outer.width - geometry.component.width,
    geometry.outer.y + geometry.outer.height - geometry.component.height,
    geometry.component.width, geometry.component.height)

internal enum class LinuxPiPHudRequest { FAILED, ALIGNED, APPLIED, STALE }

internal class LinuxPictureInPictureGeometry(private val window: ComposeWindow) {
    private var session = 0L
    fun begin() { cancel(); session = LinuxPiPGeometryNative.begin() }
    fun cancel() { if (session != 0L) LinuxPiPGeometryNative.cancel(session); session = 0L }
    val insets: Insets get() = window.insets.let { Insets(it.top, it.left, it.bottom, it.right) }

    internal fun host(component: Component = window, depth: Int = 0): NativePlayerHost? {
        if (component is NativePlayerHost) return component
        if (depth >= 64 || component !is Container) return null
        return component.components.firstNotNullOfOrNull { host(it, depth + 1) }
    }

    fun suppressHud(value: Boolean): Boolean = session != 0L && LinuxPiPGeometryNative.setHudSuppressed(session, value)

    fun sample(): LinuxPiPCanvasGeometry? = runCatching {
        val canvas = host()?.takeIf { it.isShowing && it.width > 0 && it.height > 0 } ?: return null
        val insets = insets
        // Reject old Compose/SwingPanel layout after an outer resize or inset change.
        if (canvas.width != window.width - insets.left - insets.right ||
            canvas.height != window.height - insets.top - insets.bottom) return null
        val drawable = LinuxAwtViewResolver.resolveNativeViewPointer(canvas)
        val native = LinuxPiPGeometryNative.sample(session, drawable) ?: return null
        val scale = canvas.graphicsConfiguration.defaultTransform
        if (native.size != 4 || native[2] != (canvas.width * scale.scaleX).roundToInt() ||
            native[3] != (canvas.height * scale.scaleY).roundToInt()) return null
        // Measure total effective content loss from the adopted Canvas, never from a stale sample.
        val loss = Insets(insets.top, insets.left,
            window.height - canvas.height - insets.top, window.width - canvas.width - insets.left)
        LinuxPiPCanvasGeometry(drawable, Rectangle(canvas.bounds), Rectangle(native[0], native[1], native[2], native[3]),
            Rectangle(window.bounds), loss)
    }.getOrNull()

    fun workArea(target: Rectangle): Rectangle {
        val configuration = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration }.firstOrNull { it.bounds.contains(target.centerX, target.centerY) }
            ?: window.graphicsConfiguration
        val screen = configuration.bounds
        val reserved = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        return Rectangle(screen.x + reserved.left, screen.y + reserved.top,
            screen.width - reserved.left - reserved.right, screen.height - reserved.top - reserved.bottom)
    }

    fun syncHud(geometry: LinuxPiPCanvasGeometry, remainingNanos: Long): LinuxPiPHudRequest = runCatching {
        val root = geometry.root
        LinuxPiPHudRequest.entries.getOrElse(
            LinuxPiPGeometryNative.syncHud(session, geometry.drawable, root.x, root.y, root.width, root.height, remainingNanos)
        ) { LinuxPiPHudRequest.FAILED }
    }.getOrDefault(LinuxPiPHudRequest.FAILED)

    fun hudAligned(geometry: LinuxPiPCanvasGeometry): Boolean = runCatching {
        val sample = LinuxPiPGeometryNative.observeHud(session, geometry.drawable) ?: return false
        sample.size == 8 && Rectangle(sample[0], sample[1], sample[2], sample[3]) == geometry.root &&
            Rectangle(sample[4], sample[5], sample[6], sample[7]) == geometry.root
    }.getOrDefault(false)

    fun observe(changed: () -> Unit): () -> Unit {
        val canvas = host()
        val listener = object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = changed()
            override fun componentMoved(event: ComponentEvent) = changed()
        }
        window.addComponentListener(listener)
        canvas?.addComponentListener(listener)
        return { window.removeComponentListener(listener); canvas?.removeComponentListener(listener) }
    }
}

internal object LinuxPiPGeometryNative {
    external fun setHudSuppressed(session: Long, suppressed: Boolean): Boolean
    external fun begin(): Long
    external fun cancel(session: Long)
    external fun sample(session: Long, canvas: Long): IntArray?
    external fun syncHud(session: Long, canvas: Long, x: Int, y: Int, width: Int, height: Int, remainingNanos: Long = 100_000_000L): Int
    external fun observeHud(session: Long, canvas: Long): IntArray?
}
