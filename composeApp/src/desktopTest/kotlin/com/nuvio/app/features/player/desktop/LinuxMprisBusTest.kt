package com.nuvio.app.features.player.desktop

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Each fixture owns an isolated daemon; all assertions cross D-Bus and the production JNI backend. */
class LinuxMprisBusTest {
    private val name = "org.mpris.MediaPlayer2.Nuvio"
    private val path = "/org/mpris/MediaPlayer2"
    private val player = "org.mpris.MediaPlayer2.Player"
    private class Fixture : AutoCloseable {
        val daemon = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1").start()
        val address = daemon.inputStream.bufferedReader().readLine()
        val commands = LinkedBlockingQueue<Pair<String, Double>>()
        val owner = LinuxMprisSession(nativeMprisBackend(busAddress = address))
        val client = owner.client { method, value -> commands.add(method to value) }
        val token = client.newSource()
        val active = LinuxMprisState(token = token, trackId = "/org/mpris/MediaPlayer2/track/t$token",
            title = "Episode 😀", album = "Series", artwork = "https://example.org/poster.jpg",
            season = 2, episode = 3, status = "Playing", durationUs = 120_000_000,
            positionUs = 10_000_000, canSeek = true, rate = 1.25, volume = 0.5)
        fun call(method: String, vararg args: String, dest: String = "org.mpris.MediaPlayer2.Nuvio"): String {
            val command = listOf("gdbus", "call", "--address", address, "--dest", dest,
                "--object-path", "/org/mpris/MediaPlayer2", "--method", method) + args
            val p = ProcessBuilder(command).redirectErrorStream(true).start()
            assertTrue(p.waitFor(5, TimeUnit.SECONDS), "gdbus timeout")
            val output = p.inputStream.bufferedReader().readText()
            check(p.exitValue() == 0) { output }
            return output
        }
        fun get(property: String) = call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", property)
        fun names(): String {
            val p = ProcessBuilder("gdbus", "call", "--address", address, "--dest", "org.freedesktop.DBus",
                "--object-path", "/org/freedesktop/DBus", "--method", "org.freedesktop.DBus.ListNames").start()
            check(p.waitFor(5, TimeUnit.SECONDS)); return p.inputStream.bufferedReader().readText()
        }
        fun await(condition: () -> Boolean) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!runCatching(condition).getOrDefault(false)) {
                check(System.nanoTime() < end) { "D-Bus condition timed out" }; Thread.sleep(20)
            }
        }
        override fun close() {
            owner.close(); assertTrue(owner.awaitClosed(5000))
            daemon.destroy(); assertTrue(daemon.waitFor(5, TimeUnit.SECONDS))
        }
    }
    private fun fixture(body: (Fixture) -> Unit) {
        if (DesktopHostOs.current != DesktopHostOs.LINUX || System.getProperty("nuvio.linux.nativeSmokeTest") != "true") return
        Fixture().use { f -> f.await { f.names().contains(name) }; body(f) }
    }

    @Test fun publishesStandardInterfacesAndOwnsStableName() = fixture { f ->
        val p = ProcessBuilder("gdbus", "introspect", "--address", f.address, "--dest", name, "--object-path", path).start()
        assertTrue(p.waitFor(5, TimeUnit.SECONDS))
        val result = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.exitValue())
        assertTrue(result.contains("interface org.mpris.MediaPlayer2 {"))
        assertTrue(result.contains("interface $player {"))
        assertTrue(result.contains("Seek(in  x Offset)"))
        assertTrue(f.names().contains(name))
        assertTrue(f.call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2", "Identity").contains("Nuvio"))
    }
    @Test fun metadataTypesUnicodeStatusAndReplacementAreCorrect() = fixture { f ->
        assertTrue(f.get("PlaybackStatus").contains("Stopped"))
        assertTrue(f.get("CanPlay").contains("false"))
        f.client.publish(f.active)
        f.await { f.get("PlaybackStatus").contains("Playing") }
        val metadata = f.get("Metadata")
        assertTrue(metadata.contains("objectpath '${f.active.trackId}'"), metadata)
        assertTrue(metadata.contains("int64 120000000"), metadata)
        assertTrue(metadata.contains("Episode 😀"), metadata)
        assertTrue(metadata.contains("xesam:album"))
        assertTrue(metadata.contains("nuvio:seasonNumber"))
        assertTrue(metadata.contains("xesam:trackNumber"))
        assertTrue(f.get("Position").contains("int64 10000000"))
        f.client.publish(f.active.copy(status = "Paused"))
        f.await { f.get("PlaybackStatus").contains("Paused") }
        repeat(1000) { f.client.publish(f.active.copy(title = "Newest $it", artwork = "", album = "", episode = 0, season = 0)) }
        f.await { f.get("Metadata").contains("Newest 999") }
        val newest = f.get("Metadata")
        assertFalse(newest.contains("mpris:artUrl")); assertFalse(newest.contains("xesam:album"))
        assertFalse(newest.contains("nuvio:seasonNumber")); assertFalse(newest.contains("xesam:trackNumber"))
        f.client.publish(f.active.copy(status = "Stopped"))
        f.await { f.get("PlaybackStatus").contains("Stopped") }
    }
    @Test fun transportAndCapabilitiesRoundTripThroughJni() = fixture { f ->
        f.client.publish(f.active)
        f.await { f.get("CanPlay").contains("true") }
        for (method in listOf("Play", "Pause", "PlayPause")) {
            f.call("$player.$method")
            assertEquals(method to 0.0, f.commands.poll(5, TimeUnit.SECONDS))
        }
        f.call("$player.Next"); f.call("$player.Previous")
        assertNull(f.commands.poll(100, TimeUnit.MILLISECONDS))
        f.client.publish(f.active.copy(canNext = true, canPrevious = true))
        f.await { f.get("CanGoNext").contains("true") && f.get("CanGoPrevious").contains("true") }
        for (method in listOf("Next", "Previous")) {
            f.call("$player.$method"); assertEquals(method to 0.0, f.commands.poll(5, TimeUnit.SECONDS))
        }
    }
    @Test fun seekUsesSignedMicrosecondsAndSetPositionRejectsStaleTrack() = fixture { f ->
        f.client.publish(f.active)
        f.await { f.get("CanSeek").contains("true") }
        f.call("$player.Seek", "--", "-5000000")
        assertEquals("Seek" to -5_000_000.0, f.commands.poll(5, TimeUnit.SECONDS))
        f.call("$player.SetPosition", "/wrong", "1000000")
        f.call("$player.SetPosition", f.active.trackId, "--", "-1")
        f.call("$player.SetPosition", f.active.trackId, "120000001")
        assertNull(f.commands.poll(100, TimeUnit.MILLISECONDS))
        f.call("$player.SetPosition", f.active.trackId, "30000000")
        assertEquals("SetPosition" to 30_000_000.0, f.commands.poll(5, TimeUnit.SECONDS))
        f.client.publish(f.active.copy(canSeek = false))
        f.await { f.get("CanSeek").contains("false") }
        f.call("$player.Seek", "1000000")
        assertNull(f.commands.poll(100, TimeUnit.MILLISECONDS))
    }
    @Test fun volumeAndRateRouteAndUnsupportedOperationsDoNotMutate() = fixture { f ->
        f.client.publish(f.active)
        f.await { f.get("CanPlay").contains("true") }
        f.call("org.freedesktop.DBus.Properties.Set", player, "Volume", "<0.75>")
        assertEquals("Volume" to 0.75, f.commands.poll(5, TimeUnit.SECONDS))
        f.call("org.freedesktop.DBus.Properties.Set", player, "Rate", "<1.5>")
        assertEquals("Rate" to 1.5, f.commands.poll(5, TimeUnit.SECONDS))
        assertFailsWith<IllegalStateException> { f.call("$player.Stop") }
        assertFailsWith<IllegalStateException> { f.call("$player.OpenUri", "https://example.org/a") }
        assertNull(f.commands.poll(100, TimeUnit.MILLISECONDS))
    }
    @Test fun noPlayerTeardownAndServiceExitReleaseOwnership() = fixture { f ->
        for (method in listOf("Play", "Pause", "PlayPause", "Next", "Previous")) f.call("$player.$method")
        assertNull(f.commands.poll(100, TimeUnit.MILLISECONDS))
        f.client.publish(f.active)
        f.await { f.get("Metadata").contains("Episode") }
        f.client.close()
        f.await { f.get("Metadata") == "(<@a{sv} {}>,)\n" }
        assertTrue(f.get("PlaybackStatus").contains("Stopped"))
        listOf("CanPlay", "CanPause", "CanSeek", "CanGoNext", "CanGoPrevious").forEach { assertTrue(f.get(it).contains("false")) }
        f.call("$player.Play")
        assertNull(f.commands.poll(100, TimeUnit.MILLISECONDS))
        f.owner.close(); assertTrue(f.owner.awaitClosed(5000))
        assertFalse(f.names().contains(name))
    }
    @Test fun secondServiceCannotStealOrQueueName() = fixture { f ->
        val second = nativeMprisBackend(busAddress = f.address).acquire(LinuxMprisCommandSink { _, _, _, _ -> }, {})
        second?.close()
        assertNull(second)
        assertTrue(f.names().contains(name))
        f.owner.close(); assertTrue(f.owner.awaitClosed(5000))
        assertFalse(f.names().contains(name))
    }
    @Test fun sessionBusDisappearanceIsHarmlessAndOwnerCloses() = fixture { f ->
        f.client.publish(f.active)
        f.await { f.get("PlaybackStatus").contains("Playing") }
        f.daemon.destroy(); assertTrue(f.daemon.waitFor(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        repeat(100) { f.client.publish(f.active.copy(title = "Disconnected $it")) }
        f.owner.close(); assertTrue(f.owner.awaitClosed(5000))
    }
    @Test fun propertiesChangedAndSeekedSignalsReachClients() = fixture { f ->
        val monitor = ProcessBuilder("gdbus", "monitor", "--address", f.address, "--dest", name, "--object-path", path).start()
        val lines = LinkedBlockingQueue<String>()
        val reader = Thread { monitor.inputStream.bufferedReader().useLines { it.forEach(lines::add) } }.apply { isDaemon = true; start() }
        try {
            f.await { lines.any { it.contains("owned by") } }
            f.client.publish(f.active)
            f.await { lines.any { it.contains("PropertiesChanged") && it.contains("Playing") } }
            lines.clear()
            f.client.publish(f.active.copy(positionUs = 30_000_000, seekSerial = 1))
            f.await { lines.any { it.contains("Seeked") && it.contains("30000000") } }
            assertFalse(lines.any { it.contains("PropertiesChanged") && it.contains("'Position'") })
        } finally { monitor.destroy(); monitor.waitFor(5, TimeUnit.SECONDS); reader.join(1000) }
    }
}
