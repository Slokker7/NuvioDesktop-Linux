package com.nuvio.app.features.player.desktop

import com.nuvio.app.LiveDisplayTests
import com.nuvio.app.features.player.PlayerControlsState
import java.awt.Frame
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.*

/** Real integrated libmpv + controller action paths, controlled over an isolated session bus. */
class LinuxMprisPlayerTest {
    @org.junit.Before
    fun requireLiveDisplay() = LiveDisplayTests.assumeEnabled()

    private class Fixture : AutoCloseable {
        val daemon = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1").start()
        val address = daemon.inputStream.bufferedReader().readLine()
        val owner = LinuxMprisSession(nativeMprisBackend(busAddress = address))
        val directory = Files.createTempDirectory("nuvio-mpris-player-")
        val source = directory.resolve("first.y4m")
        val replacement = directory.resolve("second.y4m")
        val host = NativePlayerHost()
        val controller = NativePlayerController(host) { owner }
        val error = AtomicReference<String?>()
        val navigation = CopyOnWriteArrayList<String>()
        var frame: Frame? = null
        init {
            for (file in listOf(source, replacement)) Files.newOutputStream(file).use { output ->
                output.write("YUV4MPEG2 W64 H64 F10:1 Ip A1:1 C420jpeg\n".toByteArray())
                val pixels = ByteArray(64 * 64 * 3 / 2) { 128.toByte() }
                repeat(600) { output.write("FRAME\n".toByteArray()); output.write(pixels) }
            }
            ui {
                frame = Frame("Nuvio MPRIS integration test").apply {
                    isAutoRequestFocus = false; focusableWindowState = false
                    add(host); setSize(320, 180); isVisible = true
                }
                controller.setControlCallbacks(onAction = { false }, onEvent = { type, value ->
                    when (type) {
                        "mediaPlay" -> { controller.play(); true }
                        "mediaPause" -> { controller.pause(); true }
                        "mediaNext", "mediaPrevious" -> { navigation += type; true }
                        "setPlaybackSpeed" -> { controller.setPlaybackSpeed(value.toFloat()); true }
                        else -> false
                    }
                }, onScrubChange = { false }, onScrubFinished = { false })
            }
        }
        fun ui(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
        fun open(file: java.nio.file.Path = source, main: Boolean = true) = ui {
            controller.attach(sourceUrl = file.toString(), sourceAudioUrl = null, sourceHeaders = emptyMap(),
                playWhenReady = false, initialPositionMs = 0,
                nvidiaRtxSuperResolutionEnabled = false, nvidiaRtxHdrEnabled = false,
                onError = { error.set(it ?: "Attach failed") }, restoreVolume = main)
        }
        fun controls(title: String = "First", next: Boolean = false, previous: Boolean = false) = ui {
            controller.updateControls(PlayerControlsState(title = title, mediaSessionCanGoNext = next,
                mediaSessionCanGoPrevious = previous))
        }
        fun snapshot(): com.nuvio.app.features.player.PlayerPlaybackSnapshot {
            val result = AtomicReference<com.nuvio.app.features.player.PlayerPlaybackSnapshot>()
            ui { result.set(controller.snapshot()) }; return result.get()
        }
        fun call(method: String, vararg args: String): String {
            val p = ProcessBuilder(listOf("gdbus", "call", "--address", address, "--dest", "org.mpris.MediaPlayer2.Nuvio",
                "--object-path", "/org/mpris/MediaPlayer2", "--method", method) + args).redirectErrorStream(true).start()
            check(p.waitFor(5, TimeUnit.SECONDS))
            val result = p.inputStream.bufferedReader().readText(); check(p.exitValue() == 0) { result }; return result
        }
        fun get(property: String) = call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", property)
        fun command(method: String, vararg args: String) = call("org.mpris.MediaPlayer2.Player.$method", *args)
        fun await(condition: () -> Boolean) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (!condition()) {
                check(error.get() == null) { "Native failure: ${error.get()}" }
                check(System.nanoTime() < end) { "Player condition timed out" }; Thread.sleep(30)
            }
        }
        override fun close() {
            ui { controller.dispose(); frame?.dispose() }
            owner.close(); assertTrue(owner.awaitClosed(5000))
            daemon.destroy(); daemon.waitFor(5, TimeUnit.SECONDS)
            directory.toFile().deleteRecursively()
        }
    }
    private fun fixture(body: (Fixture) -> Unit) {
        if (DesktopHostOs.current != DesktopHostOs.LINUX || System.getProperty("nuvio.linux.nativeSmokeTest") != "true") return
        Fixture().use(body)
    }
    @Test fun realPlayPauseToggleAndSeekUseExistingControllerPaths() = fixture { f ->
        f.open(); f.await { f.snapshot().durationMs > 0 }; f.controls()
        f.await { f.get("PlaybackStatus").contains("Paused") && f.get("CanSeek").contains("true") }
        f.command("Play"); f.await { f.snapshot().isPlaying }
        f.command("Pause"); f.await { !f.snapshot().isPlaying }
        f.command("PlayPause"); f.await { f.snapshot().isPlaying }
        f.command("PlayPause"); f.await { !f.snapshot().isPlaying }
        f.command("Seek", "10000000"); f.await { f.snapshot().positionMs in 9900..11500 }
        val track = Regex("objectpath '([^']+)'").find(f.get("Metadata"))!!.groupValues[1]
        f.command("SetPosition", track, "20000000"); f.await { f.snapshot().positionMs in 19900..20100 }
        f.command("Seek", "--", "-30000000"); f.await { f.snapshot().positionMs < 100 }
        f.command("Seek", "70000000"); Thread.sleep(100)
        assertTrue(f.snapshot().positionMs < 100, "Seek past end without Next is harmless")
        f.command("SetPosition", track, "70000000"); Thread.sleep(100)
        assertTrue(f.snapshot().positionMs < 100)
        f.command("SetPosition", "/stale", "30000000"); Thread.sleep(100)
        assertTrue(f.snapshot().positionMs < 100)
    }
    @Test fun realNextPreviousAndVolumeAreGatedAndRouted() = fixture { f ->
        f.open(); f.await { f.snapshot().durationMs > 0 }; f.controls()
        f.await { f.get("CanPlay").contains("true") }
        f.command("Next"); f.command("Previous"); Thread.sleep(100)
        assertTrue(f.navigation.isEmpty())
        f.controls(next = true, previous = true)
        f.await { f.get("CanGoNext").contains("true") && f.get("CanGoPrevious").contains("true") }
        f.command("Next"); f.command("Previous")
        f.await { f.navigation.size == 2 }
        assertEquals(listOf("mediaNext", "mediaPrevious"), f.navigation.toList())
        f.call("org.freedesktop.DBus.Properties.Set", "org.mpris.MediaPlayer2.Player", "Volume", "<0.6>")
        f.await { f.snapshot(); f.get("Volume").contains("0.6") }
        f.call("org.freedesktop.DBus.Properties.Set", "org.mpris.MediaPlayer2.Player", "Rate", "<1.5>")
        f.await { f.snapshot().playbackSpeed == 1.5f }
    }
    @Test fun realSourceReplacementChangesIdentityAndDisposalClearsSession() = fixture { f ->
        f.open(); f.await { f.snapshot().durationMs > 0 }; f.controls()
        f.await { f.get("Metadata").contains("First") }
        val oldTrack = Regex("objectpath '([^']+)'").find(f.get("Metadata"))!!.groupValues[1]
        f.open(f.replacement)
        f.await { f.snapshot().durationMs > 0 }; f.controls("Second")
        f.await { f.get("Metadata").contains("Second") }
        assertFalse(f.get("Metadata").contains(oldTrack))
        f.command("SetPosition", oldTrack, "30000000"); Thread.sleep(100)
        assertTrue(f.snapshot().positionMs < 100)
        f.ui { f.controller.dispose() }
        f.await { f.get("PlaybackStatus").contains("Stopped") && f.get("Metadata").contains("{}") }
        f.command("Play"); f.command("Seek", "10000000")
        assertTrue(f.snapshot().isLoading)
    }
    @Test fun sessionBusLossDoesNotInterruptRealPlayback() = fixture { f ->
        f.open(); f.await { f.snapshot().durationMs > 0 }; f.controls()
        f.await { f.get("CanPlay").contains("true") }
        f.command("Play"); f.await { f.snapshot().isPlaying }
        f.daemon.destroy(); assertTrue(f.daemon.waitFor(5, TimeUnit.SECONDS))
        f.await { f.snapshot().isPlaying && f.snapshot().positionMs > 700 }
        f.ui { f.controller.pause() }
        f.await { !f.snapshot().isPlaying }
        f.ui { f.controller.play() }
        f.await { f.snapshot().isPlaying }
        assertNull(f.error.get())
    }
    @Test fun trailerDoesNotAcquireMprisService() = fixture { f ->
        f.open(main = false); f.await { f.snapshot().durationMs > 0 }
        assertFailsWith<IllegalStateException> { f.get("PlaybackStatus") }
    }
}
