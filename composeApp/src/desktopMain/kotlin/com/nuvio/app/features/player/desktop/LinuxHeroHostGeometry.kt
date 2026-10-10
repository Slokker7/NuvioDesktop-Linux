package com.nuvio.app.features.player.desktop

import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener

// Like the full player, Linux needs the real Canvas allocation before playback
// readiness. Parking at 1dp until mpv advances leaves its Vulkan surface at 1x1.
internal fun heroTrailerHostUsesFullBounds(
    os: DesktopHostOs,
    playbackRevealReady: Boolean,
    playWhenReady: Boolean,
): Boolean = playWhenReady && (os == DesktopHostOs.LINUX || playbackRevealReady)

/** EDT-only attach retry. Resizing a live player remains the existing native path. */
internal class LinuxHeroHostGeometry private constructor(
    private val host: Component,
    private val onReady: () -> Unit,
) : AutoCloseable {
    private var waiting = false
    @Volatile
    var ready = false
        private set
    private val componentListener = object : ComponentAdapter() {
        override fun componentResized(event: ComponentEvent) = retry()
        override fun componentShown(event: ComponentEvent) = retry()
    }
    private val hierarchyListener = HierarchyListener { event ->
        if (event.changeFlags and (HierarchyEvent.SHOWING_CHANGED or HierarchyEvent.DISPLAYABILITY_CHANGED).toLong() != 0L) {
            retry()
        }
    }

    init {
        host.addComponentListener(componentListener)
        host.addHierarchyListener(hierarchyListener)
    }

    fun canAttach(): Boolean {
        waiting = true
        sampleBounds()
        return ready
    }

    private fun sampleBounds() {
        ready = host.isDisplayable && host.isShowing && host.width > 1 && host.height > 1
    }

    private fun retry() {
        val wasReady = ready
        sampleBounds()
        // Keep watching until native creation finishes, but retry only on an
        // eligibility transition, never on every subsequent valid resize.
        if (waiting && ready && !wasReady) onReady()
    }

    fun attached() { waiting = false }

    fun cancel() { waiting = false }

    override fun close() {
        cancel()
        host.removeComponentListener(componentListener)
        host.removeHierarchyListener(hierarchyListener)
    }

    companion object {
        fun install(os: DesktopHostOs, heroSurface: Boolean, host: Component, onReady: () -> Unit): LinuxHeroHostGeometry? =
            if (os == DesktopHostOs.LINUX && heroSurface) LinuxHeroHostGeometry(host, onReady) else null
    }
}
