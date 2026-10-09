package com.nuvio.app.features.input

import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in JNI lifetime check; reads samples but never dispatches input or requires a controller. */
class LinuxGamepadNativeTest {
    @Test
    fun `rebuilt bridge exposes controller entry points and releases sessions repeatedly`() {
        assumeTrue(DesktopHostOs.current == DesktopHostOs.LINUX &&
            System.getProperty("nuvio.linux.nativeSmokeTest") == "true")
        NativePlayerBridge.ensureNativeLibraryLoaded()
        assertEquals(0, LinuxGamepadNative.poll(0L, 255, IntArray(64)))
        LinuxGamepadNative.close(0L)
        repeat(20) {
            val handle = LinuxGamepadNative.open()
            assertTrue(handle != 0L)
            try {
                assertEquals(0, LinuxGamepadNative.poll(handle, 255, IntArray(63)))
                val state = IntArray(64)
                val mask = LinuxGamepadNative.poll(handle, 255, state)
                assertEquals(0, mask and 0xff.inv())
                for (slot in 0 until 8) {
                    val base = slot * 8
                    assertTrue(state[base + 1] in 0..255 && state[base + 2] in 0..255)
                    for (axis in 3..6) assertTrue(state[base + axis] in -32767..32767)
                    if (mask and (1 shl slot) == 0) assertTrue(state.slice(base until base + 8).all { it == 0 })
                }
            } finally { LinuxGamepadNative.close(handle) }
        }
    }
}
