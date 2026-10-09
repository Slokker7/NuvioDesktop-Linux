package com.nuvio.app.features.input

import com.nuvio.app.features.player.desktop.NativePlayerBridge
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal object LinuxGamepadNative {
    external fun open(): Long
    external fun poll(handle: Long, slotMask: Int, state: IntArray): Int
    external fun close(handle: Long)
}

/** Worker-owned JNI session. No fd or native handle is shared across worker generations. */
internal class LinuxGamepadBackend : GamepadBackend {
    private var handle = 0L
    private val neutral = LinuxGamepadNeutralGate()

    override fun poll(slotMask: Int, state: IntArray): Int {
        if (handle == 0L) {
            NativePlayerBridge.ensureNativeLibraryLoaded()
            handle = LinuxGamepadNative.open()
            check(handle != 0L) { "Cannot allocate Linux gamepad session" }
        }
        val connected = LinuxGamepadNative.poll(handle, slotMask, state)
        neutral.filter(connected, state) { samples, mask -> GamepadInput.readPressed(samples, mask).isEmpty() }
        return connected
    }

    override fun close() {
        if (handle != 0L) LinuxGamepadNative.close(handle)
        handle = 0L
        neutral.reset()
    }
}

/** Attach/reopen is muted until the existing logical thresholds report a neutral sample. */
internal class LinuxGamepadNeutralGate {
    private var previous = 0
    private var armed = 0

    fun reset() { previous = 0; armed = 0 }

    fun filter(connected: Int, state: IntArray, isNeutral: (IntArray, Int) -> Boolean) {
        armed = armed and connected and previous
        for (slot in 0 until 8) {
            val bit = 1 shl slot
            if (connected and bit != 0 && armed and bit == 0 && isNeutral(state, bit)) armed = armed or bit
            if (connected and armed and bit == 0) state.fill(0, slot * 8, slot * 8 + 8)
        }
        previous = connected
    }
}

/** Linux-only lifetime guards: serialize ownership, but never wait for native polling in stop(). */
internal class LinuxGamepadSessionGate {
    private val owner = ReentrantLock()
    private val publication = ReentrantLock()

    fun update(block: () -> Unit) = publication.withLock(block)

    fun publish(isCurrent: () -> Boolean, block: () -> Unit): Boolean = publication.withLock {
        if (!isCurrent()) return false
        block()
        true
    }

    fun run(isCurrent: () -> Boolean, block: () -> Unit) = owner.withLock {
        if (isCurrent()) block()
    }
}
