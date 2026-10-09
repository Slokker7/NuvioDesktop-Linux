package com.nuvio.app.features.input

import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LinuxGamepadBackendTest {
    private fun filter(gate: LinuxGamepadNeutralGate, mask: Int, state: IntArray) =
        gate.filter(mask, state) { samples, bit -> GamepadInput.readPressed(samples, bit, 0.3f).isEmpty() }

    @Test
    fun `platform selection leaves Windows on its existing JNI adapter and other hosts unsupported`() {
        assertSame(WindowsGamepadBackend, createGamepadBackend(DesktopHostOs.WINDOWS))
        assertIs<LinuxGamepadBackend>(createGamepadBackend(DesktopHostOs.LINUX))
        for (os in listOf(DesktopHostOs.MACOS, DesktopHostOs.UNKNOWN)) {
            assertNull(createGamepadBackend(os))
            assertFalse(gamepadBackendSupported(os))
        }
        assertTrue(gamepadBackendSupported(DesktopHostOs.WINDOWS))
        assertTrue(gamepadBackendSupported(DesktopHostOs.LINUX))
    }

    @Test
    fun `constructing and closing unopened Linux backends never loads native code`() {
        repeat(20) { LinuxGamepadBackend().apply { close(); close() } }
    }

    @Test
    fun `held attach stays muted until neutral then accepts a fresh press`() {
        val gate = LinuxGamepadNeutralGate()
        val state = IntArray(64)
        repeat(3) {
            state[0] = 0x1000
            filter(gate, 1, state)
            assertEquals(0, state[0])
        }
        filter(gate, 1, state)
        state[0] = 0x1000
        filter(gate, 1, state)
        assertEquals(setOf(GamepadButton.A), GamepadInput.readPressed(state, 1, 0.3f))
    }

    @Test
    fun `neutral baseline uses Kotlin trigger and stick thresholds`() {
        val gate = LinuxGamepadNeutralGate()
        val state = IntArray(64)
        state[1] = 60
        state[3] = 20_000
        state[5] = 20_000
        filter(gate, 1, state)
        assertTrue(state.all { it == 0 })
        state[1] = 59
        state[3] = 9_000
        state[5] = 17_000 // Below right-stick floor, even though above left-stick dead zone.
        filter(gate, 1, state)
        state[1] = 60
        state[2] = 255
        filter(gate, 1, state)
        assertEquals(setOf(GamepadButton.LeftTrigger, GamepadButton.RightTrigger), GamepadInput.readPressed(state, 1, 0.3f))
    }

    @Test
    fun `disconnect clears held direction and reconnect must become neutral again`() {
        val gate = LinuxGamepadNeutralGate()
        val state = IntArray(64)
        filter(gate, 1, state)
        state[0] = 1
        filter(gate, 1, state)
        assertEquals(setOf(GamepadButton.DpadUp), GamepadInput.readPressed(state, 1, 0.3f))
        filter(gate, 0, state)
        assertTrue(state.all { it == 0 })
        state[0] = 1
        filter(gate, 1, state)
        assertEquals(0, state[0])
        filter(gate, 1, state)
        state[0] = 1
        filter(gate, 1, state)
        assertEquals(1, state[0])
    }

    @Test
    fun `neutral state is per attachment and reset on backend reopen`() {
        val gate = LinuxGamepadNeutralGate()
        val state = IntArray(64)
        filter(gate, 1, state)
        state[0] = 0x1000
        state[8] = 0x2000
        filter(gate, 3, state)
        assertEquals(0x1000, state[0])
        assertEquals(0, state[8])
        gate.reset()
        filter(gate, 3, state)
        assertEquals(0, state[0])
    }

    @Test
    fun `controller union retains a held action until the last controller releases`() {
        val state = IntArray(64)
        state[0] = 0x1000
        state[8] = 0x1000 or 0x2000
        assertEquals(setOf(GamepadButton.A, GamepadButton.B), GamepadInput.readPressed(state, 3, 0.3f))
        state[0] = 0
        assertEquals(setOf(GamepadButton.A, GamepadButton.B), GamepadInput.readPressed(state, 3, 0.3f))
        assertTrue(GamepadInput.readPressed(state, 1, 0.3f).isEmpty())
    }

    @Test
    fun `late poll cannot publish after stop invalidates its generation`() {
        val gate = LinuxGamepadSessionGate()
        val generation = AtomicInteger()
        val polling = CountDownLatch(1)
        val finishPoll = CountDownLatch(1)
        val publications = AtomicInteger()
        val closes = AtomicInteger()
        val worker = thread {
            gate.run({ generation.get() == 0 }) {
                try {
                    polling.countDown()
                    finishPoll.await(5, TimeUnit.SECONDS)
                    gate.publish({ generation.get() == 0 }) { publications.incrementAndGet() }
                } finally { closes.incrementAndGet() }
            }
        }
        try {
            assertTrue(polling.await(5, TimeUnit.SECONDS))
            gate.update { generation.incrementAndGet() } // Does not wait for the delayed native call.
        } finally { finishPoll.countDown(); worker.join(5_000) }
        assertFalse(worker.isAlive)
        assertEquals(0, publications.get())
        assertEquals(1, closes.get())
    }

    @Test
    fun `replacement generation owns backend only after outgoing cleanup`() {
        val gate = LinuxGamepadSessionGate()
        val generation = AtomicInteger()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val trace = java.util.Collections.synchronizedList(mutableListOf<String>())
        val first = thread {
            gate.run({ generation.get() == 0 }) {
                trace += "open-old"
                entered.countDown()
                finish.await(5, TimeUnit.SECONDS)
                trace += "close-old"
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        gate.update { generation.incrementAndGet() }
        val second = thread {
            gate.run({ generation.get() == 1 }) { trace += "open-new"; trace += "close-new" }
        }
        finish.countDown()
        first.join(5_000); second.join(5_000)
        assertFalse(first.isAlive || second.isAlive)
        assertEquals(listOf("open-old", "close-old", "open-new", "close-new"), trace)
    }

    @Test
    fun `repeated lifecycle rejects stale queued generations`() {
        val gate = LinuxGamepadSessionGate()
        var generation = 0
        var opens = 0
        var closes = 0
        repeat(20) {
            val current = generation
            gate.run({ generation == current }) { opens++; closes++ }
            gate.update { generation++ }
            gate.run({ generation == current }) { error("Stopped worker opened a backend") }
            assertFalse(gate.publish({ generation == current }) { error("Stopped worker published") })
        }
        assertEquals(20, opens)
        assertEquals(opens, closes)
    }
}
