package com.nuvio.app.features.player.desktop

import java.awt.Window
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.beans.PropertyChangeListener
import javax.swing.RootPaneContainer
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
    private val shadeListener = PropertyChangeListener { publish(window?.isFocused == true) }
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
            (window as? RootPaneContainer)?.rootPane?.removePropertyChangeListener(DimmedProperty, shadeListener)
            window = owner
            window?.addWindowFocusListener(listener)
            (window as? RootPaneContainer)?.rootPane?.addPropertyChangeListener(DimmedProperty, shadeListener)
        }
        publish(owner?.isFocused == true)
    }

    private fun publish(value: Boolean) {
        // The override-redirect HUD raises itself above managed/owned windows. While the
        // Linux shade is mapped, keep the HUD hidden without falsifying actual AWT focus.
        val shadeHidden = (window as? RootPaneContainer)?.rootPane?.getClientProperty(DimmedProperty) == true
        if (!value || shadeHidden) host.onLinuxPiPCancelGesture?.invoke()
        focused = value && !shadeHidden
        onFocusChanged(focused)
    }

    fun close() {
        host.removeHierarchyListener(hierarchyListener)
        window?.removeWindowFocusListener(listener)
        (window as? RootPaneContainer)?.rootPane?.removePropertyChangeListener(DimmedProperty, shadeListener)
        window = null
    }
    companion object {
        private const val DimmedProperty = "nuvio.linux.screensaver.dimmed"

        fun setDimmed(window: Window, dimmed: Boolean) {
            (window as? RootPaneContainer)?.rootPane?.putClientProperty(DimmedProperty, dimmed)
        }
    }
}

/** Linux-only JNI surface; shared Windows/macOS player APIs stay unchanged. */
internal object LinuxPlayerControlsBridge {
    external fun setWindowFocused(handle: Long, focused: Boolean)
}
