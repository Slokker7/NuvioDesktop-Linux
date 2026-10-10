package com.nuvio.app.features.screensaver

import com.nuvio.app.LiveDisplayTests
import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.awt.Frame
import java.awt.Toolkit
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

class LinuxScreensaverInstallTest {
    @Test
    fun linuxInstallsAndRemovesInputWatch() {
        LiveDisplayTests.assumeEnabled()
        if (DesktopHostOs.current != DesktopHostOs.LINUX) return
        SwingUtilities.invokeAndWait {
            val window = Frame()
            val toolkit = Toolkit.getDefaultToolkit()
            val before = toolkit.awtEventListeners.size
            var uninstall: (() -> Unit)? = null
            try {
                uninstall = DesktopScreensaver.install(window) { error("Linux must never request shutdown") }
                assertEquals(before + 1, toolkit.awtEventListeners.size)
                uninstall()
                assertEquals(before, toolkit.awtEventListeners.size)
            } finally {
                uninstall?.invoke()
                window.dispose()
            }
        }
    }
}
