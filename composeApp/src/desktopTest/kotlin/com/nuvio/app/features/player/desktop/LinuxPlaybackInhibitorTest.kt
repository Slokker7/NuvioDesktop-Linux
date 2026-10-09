package com.nuvio.app.features.player.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.nuvio.app.features.player.EnterImmersivePlayerMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LinuxPlaybackInhibitorTest {
    private class Backend : LinuxInhibitionBackend {
        val attempts = AtomicInteger()
        val releases = AtomicInteger()
        val active = AtomicInteger()
        val leases = CopyOnWriteArrayList<Lease>()
        @Volatile var fail = false
        @Volatile var gate: CountDownLatch? = null
        inner class Lease(val lost: () -> Unit) : AutoCloseable {
            private val closed = AtomicInteger()
            var releaseGate: CountDownLatch? = null
            val releaseStarted = CountDownLatch(1)
            override fun close() {
                assertEquals(1, closed.incrementAndGet(), "Lease released twice")
                releaseStarted.countDown()
                releaseGate?.await(3, TimeUnit.SECONDS)
                active.decrementAndGet()
                releases.incrementAndGet()
            }
        }
        override fun acquire(onLost: () -> Unit): AutoCloseable {
            attempts.incrementAndGet()
            gate?.let { check(it.await(3, TimeUnit.SECONDS)) { "Test gate timed out" } }
            if (fail) error("Backend unavailable")
            active.incrementAndGet()
            return Lease(onLost).also(leases::add)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition(), "Owner did not reach expected state")
    }

    private fun exercise(block: (LinuxPlaybackInhibitor, Backend) -> Unit) {
        val backend = Backend()
        val owner = LinuxPlaybackInhibitor(backend)
        try { block(owner, backend) } finally {
            backend.gate?.countDown()
            backend.leases.forEach { it.releaseGate?.countDown() }
            owner.close()
            assertTrue(owner.awaitClosed(5_000), "Worker did not finish")
            assertEquals(0, backend.active.get(), "Inhibitor leaked")
        }
    }

    @Test fun inactiveAndRepeatedFalseOwnNothing() = exercise { owner, backend ->
        val client = owner.client()
        repeat(100) { client.setAwake(false) }
        client.close()
        assertEquals(0, backend.attempts.get())
    }

    @Test fun repeatedTrueAcquiresExactlyOnce() = exercise { owner, backend ->
        val client = owner.client()
        repeat(100) { client.setAwake(true) }
        await { backend.active.get() == 1 }
        client.close()
        await { backend.active.get() == 0 }
        assertEquals(1, backend.attempts.get())
        assertEquals(1, backend.releases.get())
    }

    @Test fun pauseReleasesOnceAndResumeReacquires() = exercise { owner, backend ->
        val client = owner.client()
        client.setAwake(true)
        await { backend.active.get() == 1 }
        repeat(100) { client.setAwake(false) }
        await { backend.releases.get() == 1 }
        client.setAwake(true)
        await { backend.attempts.get() == 2 && backend.active.get() == 1 }
    }

    @Test fun rapidIntentChangesDuringSlowAcquisitionKeepFinalIntent() = exercise { owner, backend ->
        val gate = CountDownLatch(1)
        backend.gate = gate
        val client = owner.client()
        client.setAwake(true)
        await { backend.attempts.get() == 1 }
        repeat(100) { client.setAwake(false); client.setAwake(true) }
        gate.countDown()
        await { backend.active.get() == 1 }
        client.close()
        await { backend.active.get() == 0 }
        assertEquals(1, backend.attempts.get())
    }

    @Test fun disposalDuringAcquisitionClosesLateLease() = exercise { owner, backend ->
        val gate = CountDownLatch(1)
        backend.gate = gate
        val client = owner.client()
        client.setAwake(true)
        await { backend.attempts.get() == 1 }
        client.close()
        gate.countDown()
        await { backend.releases.get() == 1 }
        assertEquals(0, backend.active.get())
        client.setAwake(true)
        assertEquals(1, backend.attempts.get(), "Disposed client must not rearm")
    }

    @Test fun ownerShutdownWhileAcquiringReleasesBeforeCompletion() = exercise { owner, backend ->
        val gate = CountDownLatch(1)
        backend.gate = gate
        owner.client().setAwake(true)
        await { backend.attempts.get() == 1 }
        owner.close()
        gate.countDown()
        assertTrue(owner.awaitClosed(4_000))
        assertEquals(1, backend.releases.get())
    }

    @Test fun activeDisposalAndRepeatedCloseReleaseOnce() = exercise { owner, backend ->
        owner.client().setAwake(true)
        await { backend.active.get() == 1 }
        repeat(100) { owner.close() }
        assertTrue(owner.awaitClosed(4_000))
        assertEquals(1, backend.releases.get())
    }

    @Test fun backendFailureIsQuietUntilANewIntentTransition() = exercise { owner, backend ->
        val client = owner.client()
        backend.fail = true
        client.setAwake(true)
        await { backend.attempts.get() == 1 }
        repeat(100) { client.setAwake(true) }
        Thread.sleep(100)
        assertEquals(1, backend.attempts.get())
        client.setAwake(false)
        backend.fail = false
        client.setAwake(true)
        await { backend.attempts.get() == 2 && backend.active.get() == 1 }
    }

    @Test fun twoSurfacesShareOneLeaseAndReleaseOnlyTheLastRequest() = exercise { owner, backend ->
        val first = owner.client()
        val second = owner.client()
        first.setAwake(true)
        second.setAwake(true)
        await { backend.active.get() == 1 }
        first.close()
        second.setAwake(true)
        assertEquals(1, backend.attempts.get())
        assertEquals(0, backend.releases.get())
        second.close()
        await { backend.releases.get() == 1 }
    }

    @Test fun disconnectInvalidatesThenReacquiresOnceWithoutPolling() = exercise { owner, backend ->
        owner.client().setAwake(true)
        await { backend.leases.size == 1 }
        backend.leases[0].lost()
        await { backend.leases.size == 2 && backend.releases.get() == 1 }
        backend.fail = true
        backend.leases[1].lost()
        await { backend.releases.get() == 2 }
        Thread.sleep(100)
        assertEquals(2, backend.attempts.get(), "Repeated service loss must not create a retry loop")
    }

    @Test fun staleLossCallbackCannotReleaseNewerOwnership() = exercise { owner, backend ->
        val client = owner.client()
        client.setAwake(true)
        await { backend.leases.size == 1 }
        val old = backend.leases[0]
        client.setAwake(false)
        await { backend.releases.get() == 1 }
        client.setAwake(true)
        await { backend.leases.size == 2 }
        old.lost()
        Thread.sleep(100)
        assertEquals(1, backend.active.get())
        assertEquals(1, backend.releases.get())
    }

    @Test fun slowReleaseCompletesBeforeNewOwnershipIsAcquired() = exercise { owner, backend ->
        val client = owner.client()
        client.setAwake(true)
        await { backend.leases.size == 1 }
        val gate = CountDownLatch(1)
        backend.leases[0].releaseGate = gate
        client.setAwake(false)
        assertTrue(backend.leases[0].releaseStarted.await(3, TimeUnit.SECONDS))
        client.setAwake(true)
        gate.countDown()
        await { backend.attempts.get() == 2 && backend.active.get() == 1 }
        assertEquals(1, backend.releases.get())
    }

    @Test fun missingBackendDoesNotRetryOnRecomposition() {
        val calls = AtomicInteger()
        val owner = LinuxPlaybackInhibitor { calls.incrementAndGet(); null }
        try {
            val client = owner.client()
            repeat(100) { client.setAwake(true) }
            await { calls.get() == 1 }
            client.close()
        } finally { owner.close(); assertTrue(owner.awaitClosed(4_000)) }
        assertEquals(1, calls.get())
    }

    @Test fun lossDuringAcquisitionCannotRetainAnInvalidLease() {
        val backend = Backend()
        val calls = AtomicInteger()
        val owner = LinuxPlaybackInhibitor { lost ->
            val lease = backend.acquire(lost)
            if (calls.incrementAndGet() == 1) lost()
            lease
        }
        try {
            owner.client().setAwake(true)
            await { calls.get() == 2 && backend.releases.get() == 1 && backend.active.get() == 1 }
        } finally { owner.close(); assertTrue(owner.awaitClosed(4_000)) }
        assertEquals(0, backend.active.get())
    }

    /** Opt-in acceptance observer for the available GNOME session; never changes power settings. */
    @Test
    fun sharedScreenAwakeEffectOwnsARealSessionInhibitor() {
        if (DesktopHostOs.current != DesktopHostOs.LINUX ||
            System.getProperty("nuvio.linux.nativeSmokeTest") != "true") return
        fun call(path: String, method: String): String {
            val process = ProcessBuilder("gdbus", "call", "--session", "--dest", "org.gnome.SessionManager",
                "--object-path", path, "--method", method).redirectErrorStream(true).start()
            val result = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "Session observer unavailable: $result" }
            return result
        }
        fun count(): Int = Regex("/org/gnome/SessionManager/Inhibitor[0-9]+")
            .findAll(call("/org/gnome/SessionManager", "org.gnome.SessionManager.GetInhibitors"))
            .count { call(it.value, "org.gnome.SessionManager.Inhibitor.GetReason").contains("Nuvio playback") }
        assertEquals(0, count(), "Previous Nuvio inhibitor leaked")
        runComposeUiTest {
            var awake by mutableStateOf(false)
            var mounted by mutableStateOf(true)
            setContent { if (mounted) EnterImmersivePlayerMode(awake) }
            waitForIdle()
            assertEquals(0, count())
            awake = true
            waitForIdle()
            waitUntil(timeoutMillis = 8_000) { count() == 1 }
            awake = false
            waitForIdle()
            waitUntil(timeoutMillis = 8_000) { count() == 0 }
            awake = true
            waitForIdle()
            waitUntil(timeoutMillis = 8_000) { count() == 1 }
            mounted = false
            waitForIdle()
            waitUntil(timeoutMillis = 8_000) { count() == 0 }
        }
        assertEquals(0, count())
    }
}
