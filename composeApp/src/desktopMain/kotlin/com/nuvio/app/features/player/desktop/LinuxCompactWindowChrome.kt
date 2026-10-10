package com.nuvio.app.features.player.desktop

import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.IdentityHashMap
import javax.swing.SwingUtilities

/** No AWT styling, peer removal, player attachment or HUD creation occurs through this API. */
internal object LinuxCompactWindowChrome {
    private class Entry(val session: LinuxCompactChromeSession, val listener: WindowAdapter)
    private val entries = IdentityHashMap<Window, Entry>() // EDT only; removed on restoration/disposal.

    fun setCompact(window: Window, enabled: Boolean) {
        check(SwingUtilities.isEventDispatchThread()) { "Linux compact chrome requires the EDT." }
        if (!window.isDisplayable || (enabled && !window.isShowing)) return
        if (!enabled && entries[window] == null) return
        runCatching {
            val entry = entries[window] ?: run {
                val session = LinuxCompactChromeSession(LinuxWindowChromeNative::begin, LinuxWindowChromeNative::end)
                val listener = object : WindowAdapter() {
                    override fun windowClosed(event: WindowEvent) {
                        // The peer is gone: release bookkeeping without querying a stale XID.
                        val removed = entries.remove(window)
                        removed?.session?.abandon()
                        window.removeWindowListener(this)
                    }
                }
                Entry(session, listener).also {
                    entries[window] = it
                    window.addWindowListener(listener)
                }
            }
            val drawable = LinuxAwtViewResolver.resolveNativeViewPointer(window)
            val success = entry.session.setCompact(drawable, enabled)
            if (!success) System.err.println("Linux compact chrome: request failed; WM acceptance is not implied.")
            if (!entry.session.active) {
                entries.remove(window)
                window.removeWindowListener(entry.listener)
            }
        }.onFailure {
            System.err.println("Linux compact chrome: ${it.message}")
            entries[window]?.takeUnless { it.session.active }?.let { entry ->
                entries.remove(window)
                window.removeWindowListener(entry.listener)
            }
        }
    }
}

/** Deterministic lifetime policy; native tokens own only saved X11 hints. */
internal class LinuxCompactChromeSession(
    private val begin: (Long) -> Long,
    private val end: (Long, Long) -> Boolean,
) {
    private var token = 0L
    val active: Boolean get() = token != 0L
    fun setCompact(drawable: Long, enabled: Boolean): Boolean {
        if (enabled) {
            if (active) return true
            if (drawable == 0L) return false
            token = begin(drawable)
            return active
        }
        if (!active) return true
        if (drawable == 0L || !end(token, drawable)) return false
        token = 0L
        return true
    }
    fun abandon() {
        if (active) end(token, 0L)
        token = 0L
    }
}

internal object LinuxWindowChromeNative {
    external fun isFloating(drawable: Long, clientWidth: Int, clientHeight: Int,
        left: Int, right: Int, top: Int, bottom: Int, checkFrame: Boolean): Boolean
    external fun begin(drawable: Long): Long
    external fun end(token: Long, drawable: Long): Boolean
}
