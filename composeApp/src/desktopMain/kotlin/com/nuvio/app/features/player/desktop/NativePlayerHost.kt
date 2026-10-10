package com.nuvio.app.features.player.desktop

import java.awt.Canvas
import java.awt.Color
import java.awt.Graphics

internal class NativePlayerHost : Canvas() {
    // Linux-only hooks; the PiP coordinator owns activation and window movement.
    var linuxPiPInteractive = false
        set(value) { if (field != value) { field = value; onLinuxPiPInteractiveChanged?.invoke(value) } }
    var onLinuxPiPInteractiveChanged: ((Boolean) -> Unit)? = null
    var onLinuxPiPCancelGesture: (() -> Unit)? = null
    var onLinuxPiPMove: ((java.awt.Point) -> Unit)? = null
    var onLinuxPiPFullscreen: (() -> Unit)? = null
    var onPeerReady: (() -> Unit)? = null
    var onDisplayableChanged: ((Boolean) -> Unit)? = null
    var onFirstPaint: (() -> Unit)? = null
    var onFirstFullSizePaint: (() -> Unit)? = null
    private var firstPaintNotified = false
    private var firstFullSizePaintNotified = false
    // Linux mpv owns an X11 child of this peer. Release it before AWT destroys
    // the Canvas (and then its parent window), regardless of Compose disposal order.
    var onBeforeLinuxPeerRemoval: (() -> Unit)? = null

    init {
        background = Color.BLACK
        ignoreRepaint = false
    }

    override fun update(graphics: Graphics) {
        paint(graphics)
    }

    override fun paint(graphics: Graphics) {
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, width, height)
        if (!firstPaintNotified) {
            firstPaintNotified = true
            onFirstPaint?.invoke()
        }
        if (!firstFullSizePaintNotified && width > 1 && height > 1) {
            firstFullSizePaintNotified = true
            onFirstFullSizePaint?.invoke()
        }
    }

    override fun addNotify() {
        super.addNotify()
        onDisplayableChanged?.invoke(true)
        repaint()
        onPeerReady?.invoke()
    }

    override fun removeNotify() {
        if (DesktopHostOs.current == DesktopHostOs.LINUX) onBeforeLinuxPeerRemoval?.invoke()
        onDisplayableChanged?.invoke(false)
        firstPaintNotified = false
        firstFullSizePaintNotified = false
        onPeerReady = null
        onFirstPaint = null
        onFirstFullSizePaint = null
        super.removeNotify()
    }
}
