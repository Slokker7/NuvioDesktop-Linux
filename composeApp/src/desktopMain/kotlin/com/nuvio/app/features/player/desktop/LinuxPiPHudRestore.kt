package com.nuvio.app.features.player.desktop

import androidx.compose.ui.window.WindowPlacement
import java.awt.Rectangle

/** Bounded EDT observations. Never reveals the HUD on a placement request alone. */
internal class LinuxPiPHudRestore(
    private val window: LinuxPictureInPictureWindow,
    private val placement: WindowPlacement,
    private val now: () -> Long,
    private val schedule: (() -> Unit) -> (() -> Unit),
    private val finished: () -> Unit,
    private val failed: (String) -> Unit,
) : AutoCloseable {
    private var cancel: (() -> Unit)? = null
    private var closed = false
    private var deadline = 0L
    private var checks = 0
    private var previous: LinuxPiPCanvasGeometry? = null
    private var observations = 0
    private val requested = mutableSetOf<Pair<Long, Rectangle>>()

    fun start() { deadline = now() + 2_000_000_000L; queue() }
    private fun queue() { if (!closed) cancel = schedule(::poll) }
    private fun fail(reason: String) { close(); failed(reason) }
    private fun poll() {
        if (closed) return
        cancel = null
        try {
            if (++checks > 128 || now() >= deadline) { fail("geometry settlement timed out"); return }
            if (!window.displayable) { fail("player window disposed"); return }
            val canvas = if (window.restoredPlacementReady(placement)) window.canvasGeometry()?.takeIf {
                it.outer == window.bounds &&
                    it.component.width == it.outer.width - it.loss.left - it.loss.right &&
                    it.component.height == it.outer.height - it.loss.top - it.loss.bottom
            } else null
            if (canvas == null || canvas != previous) {
                previous = canvas; observations = 0; queue(); return
            }
            if (!window.hudAligned(canvas)) {
                observations = 0
                val key = canvas.drawable to Rectangle(canvas.root)
                if (key !in requested) {
                    if (requested.size >= 8) { fail("HUD geometry mutation budget exhausted"); return }
                    requested.add(key)
                    when (window.syncHud(canvas, (deadline - now()).coerceAtLeast(0))) {
                        LinuxPiPHudRequest.FAILED -> { fail("HUD alignment failed"); return }
                        LinuxPiPHudRequest.STALE -> { requested.remove(key); previous = null }
                        else -> Unit
                    }
                }
                queue(); return
            }
            // Native Canvas and HUD equality must survive two later observations.
            if (!window.restoredPlacementReady(placement) || window.canvasGeometry() != canvas) {
                previous = null; observations = 0; queue(); return
            }
            if (++observations < 2) { queue(); return }
            if (now() >= deadline) { fail("final verification exceeded deadline"); return }
            if (!window.suppressHud(false)) { fail("HUD reveal rejected"); return }
            close()
            finished()
        } catch (error: Exception) { fail(error.javaClass.simpleName) }
    }
    override fun close() {
        if (closed) return
        closed = true
        cancel?.invoke(); cancel = null
        window.cancelGeometry()
    }
}
