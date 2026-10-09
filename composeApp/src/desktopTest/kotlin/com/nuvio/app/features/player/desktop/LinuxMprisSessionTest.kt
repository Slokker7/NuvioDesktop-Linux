package com.nuvio.app.features.player.desktop

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class LinuxMprisSessionTest {
    private class Backend : LinuxMprisBackend {
        val attempts = AtomicInteger()
        val releases = AtomicInteger()
        val states = CopyOnWriteArrayList<LinuxMprisState>()
        val sinks = CopyOnWriteArrayList<LinuxMprisCommandSink>()
        val losses = CopyOnWriteArrayList<() -> Unit>()
        @Volatile var fail = false
        @Volatile var gate: CountDownLatch? = null
        override fun acquire(sink: LinuxMprisCommandSink, onLost: () -> Unit): LinuxMprisLease? {
            attempts.incrementAndGet()
            gate?.await(3, TimeUnit.SECONDS)
            if (fail) return null
            sinks += sink
            losses += onLost
            return object : LinuxMprisLease {
                override fun publish(state: LinuxMprisState) { states += state }
                override fun close() { releases.incrementAndGet() }
            }
        }
    }
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) { check(System.nanoTime() < until) { "MPRIS worker timed out" }; Thread.sleep(10) }
    }
    private fun exercise(body: (LinuxMprisSession, Backend, LinkedBlockingQueue<() -> Unit>) -> Unit) {
        val backend = Backend()
        val ui = LinkedBlockingQueue<() -> Unit>()
        val owner = LinuxMprisSession(backend) { ui.add(it) }
        try { body(owner, backend, ui) }
        finally { owner.close(); assertTrue(owner.awaitClosed(5000)) }
    }
    private fun active(client: LinuxMprisSession.Client, title: String = "Title", next: Boolean = false,
        previous: Boolean = false, seek: Boolean = false): LinuxMprisState {
        val token = client.newSource()
        return LinuxMprisState(token = token, trackId = "/org/mpris/MediaPlayer2/track/t$token",
            title = title, status = "Playing", durationUs = 60_000_000, canNext = next,
            canPrevious = previous, canSeek = seek).also(client::publish)
    }

    @Test fun playbackAndMetadataTransitionsReplaceState() = exercise { owner, backend, _ ->
        val client = owner.client { _, _ -> }
        val state = active(client)
        await { backend.states.lastOrNull()?.status == "Playing" }
        client.publish(state.copy(status = "Paused", title = "Episode", artwork = "https://example.org/art.jpg"))
        await { backend.states.lastOrNull()?.status == "Paused" }
        client.publish(state.copy(status = "Stopped", title = "Replacement"))
        await { backend.states.lastOrNull()?.title == "Replacement" }
        assertEquals("", backend.states.last().artwork)
    }
    @Test fun rapidMetadataUpdatesPublishNewest() = exercise { owner, backend, _ ->
        val client = owner.client { _, _ -> }
        val state = active(client)
        repeat(1000) { client.publish(state.copy(title = "Title $it")) }
        await { backend.states.lastOrNull()?.title == "Title 999" }
    }
    @Test fun transportCommandsPreserveOrdering() = exercise { owner, backend, ui ->
        val commands = mutableListOf<String>()
        val state = active(owner.client { method, _ -> commands += method })
        await { backend.sinks.size == 1 }
        listOf("Play", "Pause", "PlayPause").forEach { backend.sinks[0].command(state.token, it, 0.0, "") }
        repeat(3) { ui.poll(5, TimeUnit.SECONDS)!!() }
        assertEquals(listOf("Play", "Pause", "PlayPause"), commands)
    }
    @Test fun nextAndPreviousOnlyRouteWhenAvailable() = exercise { owner, backend, ui ->
        val commands = mutableListOf<String>()
        val client = owner.client { method, _ -> commands += method }
        val state = active(client)
        await { backend.sinks.size == 1 }
        for (method in listOf("Next", "Previous")) { backend.sinks[0].command(state.token, method, 0.0, ""); ui.take()() }
        assertTrue(commands.isEmpty())
        client.publish(state.copy(canNext = true, canPrevious = true))
        for (method in listOf("Next", "Previous")) { backend.sinks[0].command(state.token, method, 0.0, ""); ui.take()() }
        assertEquals(listOf("Next", "Previous"), commands)
    }
    @Test fun setPositionRejectsWrongTrackAndOutOfBounds() = exercise { owner, backend, ui ->
        val values = mutableListOf<Double>()
        val state = active(owner.client { _, value -> values += value }, seek = true)
        await { backend.sinks.size == 1 }
        listOf("/wrong" to 1.0, state.trackId to -1.0, state.trackId to 60_000_001.0,
            state.trackId to 15_000_000.0).forEach { (track, value) ->
            backend.sinks[0].command(state.token, "SetPosition", value, track); ui.take()()
        }
        assertEquals(listOf(15_000_000.0), values)
    }
    @Test fun seekIsDisabledUntilSupported() = exercise { owner, backend, ui ->
        val values = mutableListOf<Double>()
        val client = owner.client { _, value -> values += value }
        val state = active(client)
        await { backend.sinks.size == 1 }
        backend.sinks[0].command(state.token, "Seek", -5_000_000.0, ""); ui.take()()
        assertTrue(values.isEmpty())
        client.publish(state.copy(canSeek = true))
        backend.sinks[0].command(state.token, "Seek", -5_000_000.0, ""); ui.take()()
        assertEquals(listOf(-5_000_000.0), values)
    }
    @Test fun disposalClearsAndQueuedCallbackCannotControlPlayer() = exercise { owner, backend, ui ->
        val commands = AtomicInteger()
        val client = owner.client { _, _ -> commands.incrementAndGet() }
        val state = active(client)
        await { backend.sinks.size == 1 }
        backend.sinks[0].command(state.token, "Play", 0.0, "")
        client.close()
        ui.take()()
        await { backend.states.lastOrNull()?.active == false }
        assertEquals(0, commands.get())
        assertEquals("Stopped", backend.states.last().status)
        assertEquals("", backend.states.last().title)
    }
    @Test fun sourceReplacementRejectsOldGeneration() = exercise { owner, backend, ui ->
        val commands = AtomicInteger()
        val client = owner.client { _, _ -> commands.incrementAndGet() }
        val old = active(client)
        await { backend.sinks.size == 1 }
        backend.sinks[0].command(old.token, "Play", 0.0, "")
        val replacement = active(client, "Replacement")
        ui.take()()
        backend.sinks[0].command(replacement.token, "Pause", 0.0, ""); ui.take()()
        assertEquals(1, commands.get())
        assertNotEquals(old.trackId, replacement.trackId)
    }
    @Test fun sourceReplacementRejectsLateOldSnapshot() = exercise { owner, backend, _ ->
        val client = owner.client { _, _ -> }
        val old = active(client, "Old")
        val newest = active(client, "Newest")
        client.publish(old)
        await { backend.states.lastOrNull()?.title == "Newest" }
        assertEquals(newest.trackId, backend.states.last().trackId)
    }
    @Test fun newerClientSupersedesOldClientWithoutAnotherService() = exercise { owner, backend, ui ->
        val oldCommands = AtomicInteger()
        val oldClient = owner.client { _, _ -> oldCommands.incrementAndGet() }
        val old = active(oldClient)
        await { backend.sinks.size == 1 }
        val current = owner.client { _, _ -> }
        active(current, "New client")
        oldClient.publish(old); oldClient.close()
        await { backend.states.lastOrNull()?.title == "New client" }
        backend.sinks[0].command(old.token, "Play", 0.0, ""); ui.take()()
        assertEquals(0, oldCommands.get())
        assertEquals(1, backend.attempts.get())
    }
    @Test fun noPlayerCommandsAreHarmless() = exercise { owner, backend, ui ->
        val commands = AtomicInteger()
        val client = owner.client { _, _ -> commands.incrementAndGet() }
        val token = client.newSource()
        await { backend.sinks.size == 1 }
        listOf("Play", "Pause", "PlayPause", "Next", "Previous", "Seek", "SetPosition", "Stop").forEach {
            backend.sinks[0].command(token, it, 0.0, ""); ui.take()()
        }
        assertEquals(0, commands.get())
    }
    @Test fun backendFailureDoesNotRetryOnSnapshots() = exercise { owner, backend, _ ->
        backend.fail = true
        val client = owner.client { _, _ -> }
        val state = active(client)
        await { backend.attempts.get() == 1 }
        repeat(100) { client.publish(state) }
        Thread.sleep(100)
        assertEquals(1, backend.attempts.get())
    }
    @Test fun connectionLossHasOneBoundedRetryAndRejectsOldConnection() = exercise { owner, backend, ui ->
        val commands = AtomicInteger()
        val state = active(owner.client { _, _ -> commands.incrementAndGet() })
        await { backend.losses.size == 1 }
        backend.sinks[0].command(state.token, "Play", 0.0, "")
        backend.losses[0]()
        await { backend.sinks.size == 2 }
        ui.take()()
        backend.losses[1]()
        await { backend.releases.get() == 2 }
        Thread.sleep(100)
        assertEquals(2, backend.attempts.get())
        assertEquals(0, commands.get())
    }
    @Test fun shutdownDuringAcquisitionReleasesLateService() = exercise { owner, backend, _ ->
        val gate = CountDownLatch(1)
        backend.gate = gate
        owner.client { _, _ -> }
        await { backend.attempts.get() == 1 }
        owner.close(); gate.countDown()
        assertTrue(owner.awaitClosed(5000))
        assertEquals(1, backend.releases.get())
    }
    @Test fun repeatedCloseReleasesOnce() = exercise { owner, backend, _ ->
        owner.client { _, _ -> }
        await { backend.sinks.size == 1 }
        repeat(100) { owner.close() }
        assertTrue(owner.awaitClosed(5000))
        assertEquals(1, backend.releases.get())
    }
    @Test fun episodeTitleUsesActiveNumbersAndDoesNotDuplicateEpisodeText() {
        val state = com.nuvio.app.features.player.PlayerControlsState(title = "Show", episodeText = "S01E01 Old",
            mediaSessionSeason = 2, mediaSessionEpisode = 3, pauseOverlayEpisodeTitle = "Current")
        assertEquals("Show — S02E03 — Current", linuxMprisTitle(state))
        assertEquals("Show — S02E03 Current", linuxMprisTitle(state.copy(
            mediaSessionSeason = 0, mediaSessionEpisode = 0, episodeText = "S02E03 Current")))
        assertEquals("Movie", linuxMprisTitle(com.nuvio.app.features.player.PlayerControlsState(title = "Movie")))
    }
    @Test fun artworkFiltersCredentialsAndSignedUrls() {
        listOf("https://user:secret@example.org/a", "https://example.org/a?token=secret", "file:///private/a",
            "https://example.org/a#secret", "invalid").forEach { assertEquals("", publicMprisArtwork(it)) }
        assertEquals("https://example.org/a.png", publicMprisArtwork("https://example.org/a.png"))
    }
}
