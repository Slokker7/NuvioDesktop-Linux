package com.nuvio.app.features.player.desktop

import java.awt.Component
import java.awt.Toolkit
import javax.swing.SwingUtilities

/** Development embedding through X11 (including XWayland), not a native Wayland surface. */
internal object LinuxAwtViewResolver {
    fun resolveNativeViewPointer(component: Component): Long {
        check(SwingUtilities.isEventDispatchThread()) { "Resolve the Linux AWT surface on the EDT." }
        check(component.isDisplayable) { "Linux AWT component peer is not ready for native playback." }
        // JAWT's platformInfo is toolkit-specific. Never cast a Wayland drawing surface as X11.
        check(Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.X11.XToolkit") {
            "Linux playback currently requires an X11/XWayland AWT toolkit."
        }
        NativePlayerBridge.ensureNativeLibraryLoaded()
        return resolveX11Drawable(component).also { check(it != 0L) { "Linux AWT X11 drawable was zero." } }
    }

    private external fun resolveX11Drawable(component: Component): Long
}
