package com.nuvio.app.features.input

import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerBridge

/** The existing eight-slot sample contract; action policy stays in GamepadInput. */
internal interface GamepadBackend : AutoCloseable {
    fun poll(slotMask: Int, state: IntArray): Int
    override fun close() = Unit
}

internal object WindowsGamepadBackend : GamepadBackend {
    override fun poll(slotMask: Int, state: IntArray): Int = NativePlayerBridge.pollGamepads(slotMask, state)
}

internal fun gamepadBackendSupported(os: DesktopHostOs): Boolean =
    os == DesktopHostOs.WINDOWS || os == DesktopHostOs.LINUX

// Construction never loads a native library or opens devices. The worker owns those operations.
internal fun createGamepadBackend(os: DesktopHostOs): GamepadBackend? = when (os) {
    DesktopHostOs.WINDOWS -> WindowsGamepadBackend
    DesktopHostOs.LINUX -> LinuxGamepadBackend()
    else -> null
}
