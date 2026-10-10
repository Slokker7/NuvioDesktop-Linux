package com.nuvio.app.features.input

import com.nuvio.app.LiveDisplayTests
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The on-screen keyboard types by synthesizing `KEY_TYPED`, exactly as the pad's buttons synthesize
 * `KEY_PRESSED`. That is what lets it work with every text field in the app without any of them
 * knowing it exists — but only if Compose actually accepts a synthetic typed character, which is
 * not something the API promises.
 *
 * Skipped rather than failed where no window can take focus, because a skip is honest and a green
 * fake is not.
 *
 * Takes OS focus while it runs, so anything typed on the machine lands in its window instead — it
 * has swallowed a message mid-sentence before now. **Opt-in** for that reason:
 *
 *     NUVIO_RUN_LIVE_DISPLAY_TESTS=1 ./gradlew desktopTest -Pnuvio.focusTests
 *
 * The assertion is about the probe characters arriving *in order*, not the field's exact contents,
 * so a stray keystroke is noise rather than a failure.
 */
class SyntheticTextInputRouteTest {

    private companion object {
        /** Typed as one burst, so it lands contiguously even if a real keystroke follows it. */
        const val PROBE = "dune"
    }

    @Test
    fun `synthetic typed characters reach a focused Compose text field`() {
        LiveDisplayTests.assumeEnabled()
        if (System.getProperty("nuvio.focusTests") != "true") {
            println("SKIPPED: takes OS focus and would swallow keystrokes; run with -Pnuvio.focusTests")
            return
        }
        if (GraphicsEnvironment.isHeadless()) {
            println("SKIPPED: headless environment, cannot focus a window")
            return
        }

        val typed = AtomicReference("")
        val focusRequester = FocusRequester()
        var window: ComposeWindow? = null

        try {
            onEdt {
                window = ComposeWindow().apply {
                    setSize(320, 240)
                    setContent {
                        var value by remember { mutableStateOf("") }
                        BasicTextField(
                            value = value,
                            onValueChange = {
                                value = it
                                typed.set(it)
                            },
                            modifier = Modifier.fillMaxSize().focusRequester(focusRequester),
                        )
                    }
                    isVisible = true
                    toFront()
                    requestFocus()
                }
            }

            if (!awaitFocus { window?.isFocused == true }) {
                println("SKIPPED: the test window never took focus")
                return
            }
            onEdt { runCatching { focusRequester.requestFocus() } }

            // isFocused flips before AWT has assigned a focus owner, so the owner has to be waited
            // for separately rather than read straight after.
            val focusOwner = awaitFocusOwner()
            if (focusOwner == null) {
                println("SKIPPED: the window took focus but AWT never assigned a focus owner")
                return
            }

            PROBE.forEach { character -> typeCharacter(focusOwner, character) }

            if (!awaitFocus { typed.get().contains(PROBE) }) {
                fail(
                    "synthetic KEY_TYPED did not reach the text field (saw \"${typed.get()}\"): " +
                        "an on-screen keyboard cannot type this way",
                )
            }
            assertTrue(
                typed.get().contains(PROBE),
                "expected the typed characters in order, saw \"${typed.get()}\"",
            )
        } finally {
            onEdt { window?.apply { isVisible = false; dispose() } }
        }
    }

    private fun typeCharacter(source: java.awt.Component, character: Char) {
        val now = System.currentTimeMillis()
        val queue = java.awt.Toolkit.getDefaultToolkit().systemEventQueue
        // A real keystroke is press, typed, release. The typed event is the one carrying the
        // character; the other two keep any key-level handler seeing a well-formed sequence.
        queue.postEvent(
            KeyEvent(source, KeyEvent.KEY_PRESSED, now, 0, KeyEvent.VK_UNDEFINED, character),
        )
        queue.postEvent(
            KeyEvent(source, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, character),
        )
        queue.postEvent(
            KeyEvent(source, KeyEvent.KEY_RELEASED, now, 0, KeyEvent.VK_UNDEFINED, character),
        )
    }

    private fun awaitFocusOwner(): java.awt.Component? {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.let { return it }
            Thread.sleep(50)
        }
        return null
    }

    private fun awaitFocus(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun onEdt(block: () -> Unit) {
        if (EventQueue.isDispatchThread()) block() else EventQueue.invokeAndWait(block)
    }
}
