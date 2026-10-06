package com.nuvio.app.features.player.desktop

import java.awt.Window
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

/** EDT-only focus tracking for the separate Linux HUD; never requests native keyboard focus. */
internal class LinuxPlayerWindowFocus(
    private val host: NativePlayerHost,
    private val onFocusChanged: (Boolean) -> Unit,
) {
    var focused = false
        private set
    private var window: Window? = null
    private val listener = object : WindowAdapter() {
        override fun windowGainedFocus(event: WindowEvent) = publish(true)
        override fun windowLostFocus(event: WindowEvent) = publish(false)
    }
    private val hierarchyListener = HierarchyListener { event ->
        if (event.changeFlags and (HierarchyEvent.PARENT_CHANGED or HierarchyEvent.DISPLAYABILITY_CHANGED).toLong() != 0L) {
            bindWindow()
        }
    }

    init {
        host.addHierarchyListener(hierarchyListener)
        bindWindow()
    }

    private fun bindWindow() {
        val owner = if (host.isDisplayable) SwingUtilities.getWindowAncestor(host) else null
        if (owner !== window) {
            window?.removeWindowFocusListener(listener)
            window = owner
            window?.addWindowFocusListener(listener)
        }
        publish(owner?.isFocused == true)
    }

    private fun publish(value: Boolean) {
        focused = value
        onFocusChanged(value)
    }

    fun close() {
        host.removeHierarchyListener(hierarchyListener)
        window?.removeWindowFocusListener(listener)
        window = null
    }
}

/** Linux-only JNI surface; shared Windows/macOS player APIs stay unchanged. */
internal object LinuxPlayerControlsBridge {
    external fun setWindowFocused(handle: Long, focused: Boolean)
}
