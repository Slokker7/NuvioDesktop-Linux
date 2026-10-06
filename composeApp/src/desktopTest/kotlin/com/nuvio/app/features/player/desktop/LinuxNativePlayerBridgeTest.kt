package com.nuvio.app.features.player.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Canvas
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.event.WindowEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayInputStream
import java.util.Base64
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer
import javax.imageio.ImageIO
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import javax.swing.SwingUtilities

class LinuxNativePlayerBridgeTest {
    private fun enabled(): Boolean = DesktopHostOs.current == DesktopHostOs.LINUX &&
        System.getProperty("nuvio.linux.nativeSmokeTest") == "true"

    @Test
    fun libraryLoadsAndRejectsMissingDrawable() {
        if (!enabled()) return
        NativePlayerBridge.ensureNativeLibraryLoaded()
        assertTrue(NativePlayerBridge.runtimeDllDir()?.resolve("libplayer_bridge.so")?.isFile == true)
        NativePlayerBridge.dispose(0L)
        NativePlayerBridge.requestSeekThumbnail(0L, 0L)
        NativePlayerBridge.requestSeekThumbnail(Long.MAX_VALUE, -1L)
        assertEquals("[]", NativePlayerBridge.subtitleTracksJson(0L))
        assertEquals("[]", NativePlayerBridge.audioTracksJson(0L))
        assertFalse(NativePlayerBridge.selectAudioTrack(0L, 1))
        assertFailsWith<IllegalStateException> { create(0L, "") }
        assertFailsWith<IllegalStateException> { NativePlayerBridge.positionMs(Long.MAX_VALUE) }
    }

    @Test
    fun resolverRejectsAnUnrealizedCanvas() {
        if (!enabled()) return
        SwingUtilities.invokeAndWait {
            assertFailsWith<IllegalStateException> {
                LinuxAwtViewResolver.resolveNativeViewPointer(Canvas())
            }
        }
    }

    @Test
    fun controllerShutsDownVideoBeforeCanvasPeerRemoval() {
        videoShutdownBeforePeerRemoval(replaceBeforeClose = false)
    }

    @Test
    fun sourceReplacementCannotHideLiveVideoFromPeerRemovalBarrier() {
        videoShutdownBeforePeerRemoval(replaceBeforeClose = true)
    }

    @Test
    fun replacementUnpublishesBeforeTeardownAndUiCommandsCannotTargetOutgoingPlayer() {
        if (!enabled() || GraphicsEnvironment.isHeadless()) return
        val source = Files.createTempFile("nuvio-linux-replacement-", ".y4m")
        val host = NativePlayerHost()
        val controller = NativePlayerController(host)
        val firstRestart = CountDownLatch(1)
        val replacementRestart = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        val handleField = NativePlayerController::class.java.getDeclaredField("handle").apply { isAccessible = true }
        val lifecycleLock = NativePlayerController::class.java.getDeclaredField("nativeProcessLifecycleLock")
            .apply { isAccessible = true }.get(null)
        var frame: Frame? = null
        try {
            writeLocalVideo(source)
            SwingUtilities.invokeAndWait {
                frame = videoFrame(host)
                controller.setControlCallbacks({ false }, { type, _ ->
                    if (type == "playbackRestart") firstRestart.countDown()
                    false
                }, { false }, { false })
                attachLocalVideo(controller, source, error)
            }
            assertTrue(firstRestart.await(20, TimeUnit.SECONDS), "No initial video restart: ${error.get()}")
            await { handleField.getLong(controller) != 0L }
            val outgoing = handleField.getLong(controller)
            val position = NativePlayerBridge.positionMs(outgoing)
            assertTrue(NativePlayerBridge.isPaused(outgoing))

            // Hold the real lifecycle monitor on the test thread, never on the EDT.
            // The attach worker cannot destroy the outgoing player until this block ends.
            synchronized(lifecycleLock) {
                SwingUtilities.invokeAndWait {
                    controller.setControlCallbacks({ false }, { type, _ ->
                        if (type == "playbackRestart") replacementRestart.countDown()
                        false
                    }, { false }, { false })
                    attachLocalVideo(controller, source, error)
                }
                // FIFO EDT dispatch ensures attachPending's unpublication has run.
                SwingUtilities.invokeAndWait {
                    assertEquals(0L, handleField.getLong(controller), "Outgoing handle is still published")
                    controller.play()
                    assertTrue(NativePlayerBridge.isPaused(outgoing), "UI play reached the outgoing player")
                    controller.seekTo(1500L)
                    controller.pause()
                    assertEquals(position, NativePlayerBridge.positionMs(outgoing), "UI seek reached the outgoing player")
                }
                // It is unpublished but still native/alive, so the later lifecycle
                // drain (rather than unpublication itself) must perform teardown.
                assertTrue(NativePlayerBridge.isPaused(outgoing))
            }

            assertTrue(replacementRestart.await(20, TimeUnit.SECONDS), "No replacement restart: ${error.get()}")
            await { handleField.getLong(controller) != 0L }
            val replacement = handleField.getLong(controller)
            assertTrue(replacement != outgoing)
            assertEquals(null, error.get())
            assertFailsWith<IllegalStateException> { NativePlayerBridge.positionMs(outgoing) }
            SwingUtilities.invokeAndWait {
                controller.play()
                assertFalse(NativePlayerBridge.isPaused(replacement))
                controller.pause()
                frame?.dispose()
                frame = null
                controller.dispose()
            }
            assertFailsWith<IllegalStateException> { NativePlayerBridge.positionMs(replacement) }
        } finally {
            SwingUtilities.invokeAndWait { controller.dispose(); frame?.dispose() }
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun nativeX11ReservationRejectsAnotherControllerUntilFullDisposal() {
        if (!enabled() || GraphicsEnvironment.isHeadless()) return
        val source = Files.createTempFile("nuvio-linux-x11-owner-", ".y4m")
        val hostA = NativePlayerHost()
        val hostB = NativePlayerHost()
        val controllerA = NativePlayerController(hostA)
        val controllerB = NativePlayerController(hostB)
        val restartA = CountDownLatch(1)
        val restartB = CountDownLatch(1)
        val rejectedB = CountDownLatch(1)
        val errorA = AtomicReference<String?>(null)
        val errorB = AtomicReference<String?>(null)
        val handleField = NativePlayerController::class.java.getDeclaredField("handle").apply { isAccessible = true }
        var frameA: Frame? = null
        var frameB: Frame? = null
        try {
            writeLocalVideo(source)
            SwingUtilities.invokeAndWait {
                frameA = videoFrame(hostA)
                frameB = videoFrame(hostB)
                controllerA.setControlCallbacks({ false }, { type, _ ->
                    if (type == "playbackRestart") restartA.countDown()
                    false
                }, { false }, { false })
                controllerB.setControlCallbacks({ false }, { type, _ ->
                    if (type == "playbackRestart") restartB.countDown()
                    false
                }, { false }, { false })
                attachLocalVideo(controllerA, source, errorA)
            }
            assertTrue(restartA.await(20, TimeUnit.SECONDS), "No player A restart: ${errorA.get()}")
            await { handleField.getLong(controllerA) != 0L }
            val handleA = handleField.getLong(controllerA)
            SwingUtilities.invokeAndWait {
                controllerB.attach(
                    sourceUrl = source.toString(), sourceAudioUrl = null, sourceHeaders = emptyMap(),
                    playWhenReady = false, initialPositionMs = 0L,
                    nvidiaRtxSuperResolutionEnabled = false, nvidiaRtxHdrEnabled = false,
                    onError = { errorB.set(it); rejectedB.countDown() },
                )
            }
            assertTrue(rejectedB.await(10, TimeUnit.SECONDS), "Second controller was not rejected")
            assertTrue(errorB.get()?.contains("Linux X11 player is already active") == true, "${errorB.get()}")
            assertEquals(0L, handleField.getLong(controllerB))
            assertEquals(1L, restartB.count)
            assertTrue(NativePlayerBridge.isPaused(handleA), "Rejected creation damaged player A")
            // Also bypass Kotlin serialization: the ownership rule must live in JNI.
            var drawableB = 0L
            SwingUtilities.invokeAndWait { drawableB = LinuxAwtViewResolver.resolveNativeViewPointer(hostB) }
            val rejection = assertFailsWith<IllegalStateException> { create(drawableB, source.toString()) }
            assertTrue(rejection.message?.contains("Linux X11 player is already active") == true)
            SwingUtilities.invokeAndWait { controllerA.dispose(); controllerA.dispose() }
            assertFailsWith<IllegalStateException> { NativePlayerBridge.positionMs(handleA) }
            errorB.set(null)
            SwingUtilities.invokeAndWait { attachLocalVideo(controllerB, source, errorB) }
            assertTrue(restartB.await(20, TimeUnit.SECONDS), "No player B restart after release: ${errorB.get()}")
            await { handleField.getLong(controllerB) != 0L }
            val handleB = handleField.getLong(controllerB)
            assertEquals(null, errorB.get())
            // Disposing A again must not release B's newly acquired reservation.
            SwingUtilities.invokeAndWait { controllerA.dispose() }
            NativePlayerBridge.dispose(handleA)
            assertFailsWith<IllegalStateException> { create(drawableB, source.toString()) }
            SwingUtilities.invokeAndWait {
                controllerB.play()
                assertFalse(NativePlayerBridge.isPaused(handleB))
                controllerB.pause()
                frameB?.dispose()
                frameB = null
                controllerB.dispose()
            }
            assertFailsWith<IllegalStateException> { NativePlayerBridge.positionMs(handleB) }
        } finally {
            SwingUtilities.invokeAndWait {
                controllerA.dispose(); controllerB.dispose(); frameA?.dispose(); frameB?.dispose()
            }
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun partialInitializationFailureReleasesNativeX11Reservation() {
        if (!enabled() || GraphicsEnvironment.isHeadless()) return
        val source = Files.createTempFile("nuvio-linux-partial-init-", ".wav")
        val host = NativePlayerHost()
        var frame: Frame? = null
        var handle = 0L
        try {
            Files.write(source, silentWav())
            var drawable = 0L
            SwingUtilities.invokeAndWait {
                frame = videoFrame(host)
                drawable = LinuxAwtViewResolver.resolveNativeViewPointer(host)
            }
            // Valid mpv initialization, then a trapped GTK query of a nonexistent
            // Canvas forces creation to unwind before a handle can be published.
            val failure = assertFailsWith<IllegalStateException> {
                create(0xffffffffL, source.toString(), controlsPageUrl = NativePlayerBridge.controlsPageUrl)
            }
            assertTrue(failure.message?.contains("Canvas disappeared before controls creation") == true, "${failure.message}")
            val loaded = CountDownLatch(1)
            handle = create(drawable, source.toString(), NativePlayerEventSink { type, _ ->
                if (type == "fileLoaded") loaded.countDown()
            })
            assertTrue(loaded.await(10, TimeUnit.SECONDS), "Reservation leaked after partial initialization")
        } finally {
            if (handle != 0L) NativePlayerBridge.dispose(handle)
            SwingUtilities.invokeAndWait { frame?.dispose() }
            Files.deleteIfExists(source)
        }
    }

    private fun videoShutdownBeforePeerRemoval(replaceBeforeClose: Boolean) {
        if (!enabled() || GraphicsEnvironment.isHeadless()) return
        val source = Files.createTempFile("nuvio-linux-shutdown-", ".y4m")
        val host = NativePlayerHost()
        val controller = NativePlayerController(host)
        val restarted = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        var frame: Frame? = null
        try {
            // Real video VO/X11 teardown, without a codec generator or network fixture.
            writeLocalVideo(source)
            SwingUtilities.invokeAndWait {
                frame = Frame("Nuvio Linux video shutdown test").apply {
                    isAutoRequestFocus = false
                    focusableWindowState = false
                    add(host)
                    setSize(320, 180)
                    isVisible = true
                }
                controller.setControlCallbacks({ false }, { type, _ ->
                    if (type == "playbackRestart") restarted.countDown()
                    false
                }, { false }, { false })
                controller.attach(
                    sourceUrl = source.toString(), sourceAudioUrl = null, sourceHeaders = emptyMap(),
                    playWhenReady = false, initialPositionMs = 0L,
                    nvidiaRtxSuperResolutionEnabled = false, nvidiaRtxHdrEnabled = false,
                    onError = { error.set(it ?: "Native attach failed") },
                )
            }
            assertTrue(restarted.await(20, TimeUnit.SECONDS), "No real video restart: ${error.get()}")
            assertEquals(null, error.get())
            // Observe the published JNI handle without adding a production test-only API.
            val handleField = NativePlayerController::class.java.getDeclaredField("handle").apply { isAccessible = true }
            // mpv can restart before native create finishes creating the GTK HUD.
            await { handleField.getLong(controller) != 0L }
            val nativeHandle = handleField.getLong(controller)
            assertTrue(nativeHandle != 0L)
            assertEquals("[]", NativePlayerBridge.audioTracksJson(nativeHandle), "Video-only fixture has audio tracks")
            assertFalse(NativePlayerBridge.selectAudioTrack(nativeHandle, 1))
            if (replaceBeforeClose) {
                SwingUtilities.invokeAndWait {
                    controller.attach(
                        sourceUrl = source.toString(), sourceAudioUrl = null, sourceHeaders = emptyMap(),
                        playWhenReady = false, initialPositionMs = 0L,
                        nvidiaRtxSuperResolutionEnabled = false, nvidiaRtxHdrEnabled = false,
                        onError = { error.set(it ?: "Native replacement failed") },
                    )
                }
            }
            var barrierChecked = false
            SwingUtilities.invokeAndWait {
                val shutdown = checkNotNull(host.onBeforeLinuxPeerRemoval)
                host.onBeforeLinuxPeerRemoval = {
                    assertTrue(host.isDisplayable)
                    shutdown()
                    assertTrue(host.isDisplayable, "Canvas peer disappeared before native shutdown returned")
                    assertFailsWith<IllegalStateException> { NativePlayerBridge.positionMs(nativeHandle) }
                    barrierChecked = true
                }
                frame?.dispose()
                frame = null
                controller.dispose() // Idempotent Compose disposal after AWT disposal.
            }
            assertTrue(barrierChecked)
        } finally {
            SwingUtilities.invokeAndWait { controller.dispose(); frame?.dispose() }
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun x11CanvasSupportsRealMpvLifecycleAndCommands() {
        if (!enabled()) return
        if (GraphicsEnvironment.isHeadless()) {
            println("SKIPPED: no display; real JAWT Canvas test requires X11/XWayland.")
            return
        }
        // Audio-only local fixture exercises the lifecycle without claiming Nuvio video works.
        // The temporary window cannot take focus; the audio output is null and opens no device.
        val source = Files.createTempFile("nuvio-linux-🟦-", ".wav")
        val subtitles = List(2) { Files.createTempFile("nuvio-linux-字幕-\"slash\\line\n🟦-", ".srt") }
        val loaded = CountDownLatch(1)
        val host = NativePlayerHost()
        var frame: Frame? = null
        var drawable = 0L
        var handle = 0L
        try {
            Files.write(source, silentWav())
            subtitles.forEach {
                Files.writeString(it, "1\n00:00:00,000 --> 00:00:04,000\nLinux JNI subtitle smoke test\n\n")
            }
            SwingUtilities.invokeAndWait {
                frame = Frame("Nuvio Linux JNI lifecycle test").apply {
                    isAutoRequestFocus = false
                    focusableWindowState = false
                    add(host)
                    setSize(320, 180)
                    isVisible = true
                }
                drawable = LinuxAwtViewResolver.resolveNativeViewPointer(host)
            }
            assertTrue(drawable > 0L)
            handle = create(drawable, source.toString(), NativePlayerEventSink { type, _ ->
                if (type == "fileLoaded") loaded.countDown()
            })
            assertTrue(handle != 0L)
            assertTrue(loaded.await(10, TimeUnit.SECONDS), "No fileLoaded event for local WAV")
            val audio = Json.parseToJsonElement(NativePlayerBridge.audioTracksJson(handle)).jsonArray.single().jsonObject
            assertAudioTrackSchema(audio)
            assertEquals(0, audio.getValue("index").jsonPrimitive.int)
            assertEquals("Track 1 (Mono, WAV)", audio.getValue("label").jsonPrimitive.content)
            assertEquals("", audio.getValue("language").jsonPrimitive.content)
            assertTrue(audio.getValue("selected").jsonPrimitive.boolean)
            assertTrue(NativePlayerBridge.selectAudioTrack(handle, audio.getValue("id").jsonPrimitive.content.toInt()))
            await { NativePlayerBridge.durationMs(handle) in 4900L..5100L }
            assertTrue(NativePlayerBridge.isPaused(handle))
            await { NativePlayerBridge.positionMs(handle) in 900L..1100L }
            NativePlayerBridge.setPaused(handle, false)
            assertFalse(NativePlayerBridge.isPaused(handle))
            NativePlayerBridge.setPaused(handle, true)
            NativePlayerBridge.seekTo(handle, 2500L)
            await(state = {
                "position=${NativePlayerBridge.positionMs(handle)}, paused=${NativePlayerBridge.isPaused(handle)}, " +
                    "loading=${NativePlayerBridge.isLoading(handle)}, ended=${NativePlayerBridge.isEnded(handle)}"
            }) { NativePlayerBridge.positionMs(handle) in 2400L..2600L }
            NativePlayerBridge.seekBy(handle, -500L)
            await { NativePlayerBridge.positionMs(handle) in 1900L..2100L }
            NativePlayerBridge.setSpeed(handle, 1.25f)
            assertEquals(1.25f, NativePlayerBridge.speed(handle))
            NativePlayerBridge.setVolume(handle, 25f)
            assertEquals(25f, NativePlayerBridge.volume(handle))
            NativePlayerBridge.setMute(handle, true)
            assertTrue(NativePlayerBridge.isMuted(handle))
            assertFalse(NativePlayerBridge.isEnded(handle))
            await { !NativePlayerBridge.isLoading(handle) }
            assertTrue(NativePlayerBridge.bufferedPositionMs(handle) >= NativePlayerBridge.positionMs(handle))
            NativePlayerBridge.setSubtitleDelayMs(handle, 100)
            assertTrue(NativePlayerBridge.selectSubtitleTrack(handle, -1))
            assertTrue(Json.parseToJsonElement(NativePlayerBridge.subtitleTracksJson(handle)).jsonArray.isEmpty())
            // Local SRTs exercise all external-subtitle JNI exports without network access.
            NativePlayerBridge.addSubtitleUrl(handle, "")
            subtitles.forEach { NativePlayerBridge.addSubtitleUrl(handle, it.toString()) }
            val tracksJson = NativePlayerBridge.subtitleTracksJson(handle)
            assertTrue(tracksJson.all { it.code < 128 }, "Subtitle JSON must be safe for JNI NewStringUTF")
            val tracks = Json.parseToJsonElement(tracksJson).jsonArray
            assertEquals(subtitles.size, tracks.size)
            tracks.forEachIndexed { index, entry ->
                val track = entry.jsonObject
                assertEquals(setOf("index", "id", "label", "language", "selected", "forced"), track.keys)
                assertEquals(index, track.getValue("index").jsonPrimitive.int)
                val id = track.getValue("id").jsonPrimitive
                assertTrue(id.isString && id.content.toLongOrNull() != null)
                val label = track.getValue("label").jsonPrimitive
                assertTrue(label.isString && label.content.isNotBlank())
                assertTrue(track.getValue("language").jsonPrimitive.isString)
                assertFalse(track.getValue("selected").jsonPrimitive.isString)
                track.getValue("selected").jsonPrimitive.boolean
                assertFalse(track.getValue("forced").jsonPrimitive.isString)
                track.getValue("forced").jsonPrimitive.boolean
            }
            assertTrue(tracks.any { it.jsonObject.getValue("selected").jsonPrimitive.boolean })
            NativePlayerBridge.clearExternalSubtitles(handle)
            assertTrue(Json.parseToJsonElement(NativePlayerBridge.subtitleTracksJson(handle)).jsonArray.isEmpty())
            NativePlayerBridge.addSubtitleUrl(handle, subtitles.first().toString())
            NativePlayerBridge.clearExternalSubtitlesAndSelect(handle, -1)
            assertTrue(Json.parseToJsonElement(NativePlayerBridge.subtitleTracksJson(handle)).jsonArray.isEmpty())
            NativePlayerBridge.clearExternalSubtitles(handle)
            NativePlayerBridge.setSubtitleAssStyleMode(handle, "yes", 1.0)
            NativePlayerBridge.applySubtitleStyle(
                handle, "#FFFFFFFF", "#00000000", "#FF000000", 2f, false, 48f, 100, "sans-serif",
            )
            NativePlayerBridge.setResizeMode(handle, 0)
            NativePlayerBridge.dispose(handle)
            NativePlayerBridge.dispose(handle)
            assertFailsWith<IllegalStateException> { NativePlayerBridge.durationMs(handle) }
            assertFailsWith<IllegalStateException> { NativePlayerBridge.subtitleTracksJson(handle) }
            handle = 0L
        } finally {
            if (handle != 0L) NativePlayerBridge.dispose(handle)
            SwingUtilities.invokeAndWait { frame?.dispose() }
            Files.deleteIfExists(source)
            subtitles.forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun multipleAudioTracksReachHudAndSelectionAndReplacementUseLiveTrackIds() {
        if (!enabled() || GraphicsEnvironment.isHeadless()) return
        val directory = Files.createTempDirectory("nuvio-linux-audio-tracks-")
        val source = directory.resolve("two-tracks.mka")
        val single = directory.resolve("single.wav")
        val host = NativePlayerHost()
        val controller = NativePlayerController(host)
        val loaded = CountDownLatch(1)
        val selectedFromHud = CountDownLatch(1)
        val selectionResult = AtomicReference<Boolean?>(null)
        val states = LinkedBlockingQueue<Int>()
        val menuRows = LinkedBlockingQueue<Int>()
        val error = AtomicReference<String?>(null)
        val handleField = NativePlayerController::class.java.getDeclaredField("handle").apply { isAccessible = true }
        var frame: Frame? = null
        var recreated = 0L
        try {
            LinuxAudioTrackFixture.write(source)
            Files.write(single, silentWav())
            SwingUtilities.invokeAndWait {
                frame = videoFrame(host)
                controller.setControlCallbacks({ false }, { type, value ->
                    when (type) {
                        "fileLoaded" -> loaded.countDown()
                        "audioParityState" -> states.add(value.toInt())
                        "audioParityMenu" -> menuRows.add(value.toInt())
                        "selectAudioTrack" -> {
                            // Same logical-index resolution used by the shared player runtime.
                            selectionResult.set(controller.selectAudioTrack(value.toInt()))
                            selectedFromHud.countDown()
                            return@setControlCallbacks true
                        }
                    }
                    false
                }, { false }, { false })
                attachLocalVideo(controller, source, error)
            }
            assertTrue(loaded.await(20, TimeUnit.SECONDS), "No multi-audio fileLoaded: ${error.get()}")
            await { handleField.getLong(controller) != 0L }
            val handle = handleField.getLong(controller)
            fun tracks(current: Long) = Json.parseToJsonElement(NativePlayerBridge.audioTracksJson(current)).jsonArray
            val initialJson = NativePlayerBridge.audioTracksJson(handle)
            assertTrue(initialJson.all { it.code < 128 }, "Audio JSON is unsafe for JNI NewStringUTF")
            val initial = tracks(handle)
            assertEquals(2, initial.size)
            initial.forEachIndexed { index, track ->
                assertAudioTrackSchema(track.jsonObject)
                assertEquals(index, track.jsonObject.getValue("index").jsonPrimitive.int)
                assertFalse(track.jsonObject.getValue("forced").jsonPrimitive.boolean)
            }
            assertEquals(LinuxAudioTrackFixture.title.trim() + " (WAV)", initial[0].jsonObject.getValue("label").jsonPrimitive.content)
            assertEquals("eng", initial[0].jsonObject.getValue("language").jsonPrimitive.content)
            // Matroska PCM carries a channel count but no layout mask. mpv exposes
            // the undecoded layout as unknown2; Windows preserves that raw name.
            assertEquals("fra (unknown2, WAV)", initial[1].jsonObject.getValue("label").jsonPrimitive.content)
            assertEquals("fra", initial[1].jsonObject.getValue("language").jsonPrimitive.content)
            val ids = initial.map { it.jsonObject.getValue("id").jsonPrimitive.content.toInt() }
            assertEquals(listOf(1, 2), ids, "Fixture must distinguish logical indices from mpv IDs")
            assertEquals(listOf(true, false), initial.map { it.jsonObject.getValue("selected").jsonPrimitive.boolean })

            installAudioHudProbe(handle)
            awaitAudioHudState(states, 20)
            NativePlayerBridge.runJavaScript(handle, """
                document.querySelector('[data-command="audio"]').click();
                const rows = document.querySelectorAll('#audioTrackList .track-row');
                window.webkit.messageHandlers.player.postMessage({type:'audioParityMenu',value:rows.length});
                if (rows.length === 2) rows[1].click();
            """.trimIndent())
            assertEquals(2, menuRows.poll(10, TimeUnit.SECONDS), "Existing Audio menu did not enumerate both tracks")
            assertTrue(selectedFromHud.await(10, TimeUnit.SECONDS), "HUD did not send audio selection")
            assertEquals(true, selectionResult.get())
            await { tracks(handle).single { it.jsonObject.getValue("selected").jsonPrimitive.boolean }
                .jsonObject.getValue("id").jsonPrimitive.content.toInt() == ids[1] }
            awaitAudioHudState(states, 21)
            for (invalid in listOf(-1, 0, Int.MAX_VALUE)) assertFalse(NativePlayerBridge.selectAudioTrack(handle, invalid))
            assertTrue(tracks(handle)[1].jsonObject.getValue("selected").jsonPrimitive.boolean)
            assertTrue(NativePlayerBridge.selectAudioTrack(handle, ids[0]))
            awaitAudioHudState(states, 20)

            // Replace the source in the real controller; its new native HUD must
            // report one current track, with no state cached from the old media.
            SwingUtilities.invokeAndWait { attachLocalVideo(controller, single, error) }
            await { handleField.getLong(controller).let { it != 0L && it != handle } }
            val replacement = handleField.getLong(controller)
            await { tracks(replacement).size == 1 }
            assertFalse(NativePlayerBridge.selectAudioTrack(replacement, ids[1]), "Stale source ID was accepted")
            states.clear()
            installAudioHudProbe(replacement)
            awaitAudioHudState(states, 10)
            SwingUtilities.invokeAndWait {
                assertEquals(1, controller.getAudioTracks().size)
                assertFalse(controller.selectAudioTrack(1))
                controller.dispose()
            }
            assertEquals(null, error.get())
            var drawable = 0L
            SwingUtilities.invokeAndWait { drawable = LinuxAwtViewResolver.resolveNativeViewPointer(host) }
            val recreatedLoaded = CountDownLatch(1)
            recreated = create(drawable, single.toString(), NativePlayerEventSink { type, _ ->
                if (type == "fileLoaded") recreatedLoaded.countDown()
            })
            assertTrue(recreatedLoaded.await(10, TimeUnit.SECONDS))
            assertEquals(1, tracks(recreated).size)
            assertTrue(tracks(recreated).single().jsonObject.getValue("selected").jsonPrimitive.boolean)
            assertFalse(NativePlayerBridge.selectAudioTrack(recreated, ids[1]))
            NativePlayerBridge.dispose(recreated)
            recreated = 0L
        } finally {
            if (recreated != 0L) NativePlayerBridge.dispose(recreated)
            SwingUtilities.invokeAndWait { controller.dispose(); frame?.dispose() }
            Files.deleteIfExists(source)
            Files.deleteIfExists(single)
            Files.deleteIfExists(directory)
        }
    }

    private fun assertAudioTrackSchema(track: kotlinx.serialization.json.JsonObject) {
        assertEquals(setOf("index", "id", "label", "language", "selected", "forced"), track.keys)
        assertFalse(track.getValue("index").jsonPrimitive.isString)
        assertTrue(track.getValue("id").jsonPrimitive.isString)
        assertTrue(track.getValue("id").jsonPrimitive.content.toLongOrNull() != null)
        assertTrue(track.getValue("label").jsonPrimitive.isString)
        assertTrue(track.getValue("language").jsonPrimitive.isString)
        for (key in listOf("selected", "forced")) {
            assertFalse(track.getValue(key).jsonPrimitive.isString)
            track.getValue(key).jsonPrimitive.boolean
        }
    }

    private fun installAudioHudProbe(handle: Long) {
        NativePlayerBridge.runJavaScript(handle, """
            const originalAudioPlayerUpdate = window.playerUpdate;
            window.playerUpdate = update => {
                originalAudioPlayerUpdate(update);
                const tracks = update.audioTracks;
                const valid = Array.isArray(tracks) && tracks.every((track, index) =>
                    Object.keys(track).sort().join(',') === 'forced,id,index,label,language,selected' &&
                    track.index === index && typeof track.id === 'string' && /^\d+${'$'}/.test(track.id) &&
                    typeof track.label === 'string' && typeof track.language === 'string' &&
                    typeof track.selected === 'boolean' && typeof track.forced === 'boolean');
                window.webkit.messageHandlers.player.postMessage({type:'audioParityState',
                    value:valid ? tracks.length * 10 + tracks.findIndex(track => track.selected) : -999});
            };
        """.trimIndent())
    }

    private fun awaitAudioHudState(states: LinkedBlockingQueue<Int>, expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            val state = states.poll((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
            assertTrue(state != null, "No playerUpdate audio state $expected")
            assertTrue(state != -999, "Invalid audio schema in playerUpdate")
            if (state == expected) return
        }
    }

    @Test
    fun windowFocusEventsHideAndRestoreHudWithoutReplacingPlayer() {
        if (!enabled() || GraphicsEnvironment.isHeadless()) return
        val source = Files.createTempFile("nuvio-linux-hud-focus-", ".y4m")
        val host = NativePlayerHost()
        val controller = NativePlayerController(host)
        val restart = CountDownLatch(1)
        val visibility = LinkedBlockingQueue<Int>()
        val error = AtomicReference<String?>(null)
        val handleField = NativePlayerController::class.java.getDeclaredField("handle").apply { isAccessible = true }
        var frame: Frame? = null
        try {
            writeLocalVideo(source)
            SwingUtilities.invokeAndWait {
                frame = videoFrame(host)
                controller.setControlCallbacks({ false }, { type, value ->
                    if (type == "playbackRestart") restart.countDown()
                    if (type == "overlayFocusState") visibility.add(value.toInt())
                    false
                }, { false }, { false })
                attachLocalVideo(controller, source, error)
            }
            assertTrue(restart.await(20, TimeUnit.SECONDS), "No video restart: ${error.get()}")
            await { handleField.getLong(controller) != 0L }
            val handle = handleField.getLong(controller)
            NativePlayerBridge.runJavaScript(handle, """
                window.focusTestMarker = 42;
                const reportFocusVisibility = () => window.webkit.messageHandlers.player.postMessage({
                    type:'overlayFocusState', value:window.focusTestMarker === 42 ? (document.hidden ? 0 : 1) : -1
                });
                document.addEventListener('visibilitychange', reportFocusVisibility);
                reportFocusVisibility();
            """.trimIndent())
            assertEquals(0, visibility.poll(10, TimeUnit.SECONDS), "Unfocused HUD was initially visible")
            // Exercise the actual registered AWT -> controller -> JNI -> GTK path
            // without stealing the user's desktop focus. Real Mutter focus needs manual testing.
            repeat(3) {
                SwingUtilities.invokeAndWait {
                    val owner = requireNotNull(frame)
                    assertTrue(owner.windowFocusListeners.isNotEmpty(), "No owning-window focus listener")
                    owner.windowFocusListeners.forEach { listener ->
                        listener.windowGainedFocus(WindowEvent(owner, WindowEvent.WINDOW_GAINED_FOCUS))
                    }
                }
                assertEquals(1, visibility.poll(10, TimeUnit.SECONDS), "HUD did not map on focus gain")
                SwingUtilities.invokeAndWait {
                    controller.play()
                    assertFalse(NativePlayerBridge.isPaused(handle))
                    controller.pause()
                    val owner = requireNotNull(frame)
                    owner.windowFocusListeners.forEach { listener ->
                        listener.windowLostFocus(WindowEvent(owner, WindowEvent.WINDOW_LOST_FOCUS))
                    }
                }
                assertEquals(0, visibility.poll(10, TimeUnit.SECONDS), "HUD did not unmap on focus loss")
                assertEquals(handle, handleField.getLong(controller), "Focus change replaced the player")
                assertTrue(NativePlayerBridge.isPaused(handle))
            }
            SwingUtilities.invokeAndWait {
                controller.dispose()
                assertTrue(requireNotNull(frame).windowFocusListeners.isEmpty(), "Focus listener leaked after disposal")
                frame?.dispose()
                frame = null
            }
            assertFailsWith<IllegalStateException> { NativePlayerBridge.positionMs(handle) }
        } finally {
            SwingUtilities.invokeAndWait { controller.dispose(); frame?.dispose() }
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun x11WebKitOverlayLoadsSiblingAssetsAndDeliversQueuedMessages() {
        if (!enabled()) return
        if (GraphicsEnvironment.isHeadless()) {
            println("SKIPPED: no display; WebKit overlay test requires X11/XWayland.")
            return
        }
        // A tiny local fixture tests the native transport, not the full HUD or video compositing.
        val directory = Files.createTempDirectory("nuvio-webkit-🟦-")
        val source = directory.resolve("silent.wav")
        val subtitle = directory.resolve("subtitle-字幕-🟦.srt")
        val page = directory.resolve("controls.html")
        val script = directory.resolve("controls.js")
        val delivered = CountDownLatch(1)
        val back = CountDownLatch(1)
        val resized = CountDownLatch(1)
        val moved = CountDownLatch(1)
        val loaded = CountDownLatch(1)
        val subtitleTracksDelivered = CountDownLatch(1)
        val host = NativePlayerHost()
        var frame: Frame? = null
        var handle = 0L
        try {
            Files.write(source, silentWav())
            Files.writeString(subtitle, "1\n00:00:00,000 --> 00:00:04,000\nLinux JNI subtitle smoke test\n\n")
            Files.writeString(page, """<!doctype html><html><body style="background:transparent"><script src="controls.js"></script></body></html>""")
            Files.writeString(script, """
                const send = (type, value) => window.webkit.messageHandlers.player.postMessage({type, value});
                window.order = [];
                window.playerControls = next => { window.controls = next; };
                window.playerUpdate = next => { window.playback = next; };
                window.addEventListener('resize', () => {
                    if (window.initialWidth && window.innerWidth > window.initialWidth) send('overlayResized', 1);
                });
                // Give startup JNI calls a chance to queue; the assertions also work if ready wins.
                setTimeout(() => send('controlsReady', 0), 1000);
            """.trimIndent())
            var drawable = 0L
            SwingUtilities.invokeAndWait {
                frame = Frame("Nuvio Linux WebKit transport test").apply {
                    isAutoRequestFocus = false
                    focusableWindowState = false
                    add(host)
                    setSize(320, 180)
                    isVisible = true
                }
                drawable = LinuxAwtViewResolver.resolveNativeViewPointer(host)
            }
            handle = create(drawable, source.toString(), NativePlayerEventSink { type, value ->
                when {
                    type == "overlayTest" && value == 42.0 -> delivered.countDown()
                    type == "back" && value == 3.0 -> back.countDown()
                    type == "overlayResized" && value == 1.0 -> resized.countDown()
                    type == "overlayMoved" && value == 1.0 -> moved.countDown()
                    type == "fileLoaded" -> loaded.countDown()
                    type == "overlaySubtitleTracks" && value == 1.0 -> subtitleTracksDelivered.countDown()
                }
            }, page.toUri().toString())
            // Direct JNI fixtures have no controller to supply owning-window focus.
            LinuxPlayerControlsBridge.setWindowFocused(handle, true)
            NativePlayerBridge.updateControls(handle, """{"sequence":1}""")
            NativePlayerBridge.updateControls(
                handle,
                """{"sequence":2,"title":"quote \" slash \\ newline \n 🟦\u2028"}""".replace("\\u2028", "\u2028"),
            )
            NativePlayerBridge.runJavaScript(handle, "window.order.push(1)")
            NativePlayerBridge.runJavaScript(handle, """
                window.order.push(2);
                const valid = window.order.join(',') === '1,2' && window.controls.sequence === 2 &&
                    window.controls.title.includes('🟦') && window.controls.title.includes('quote "') &&
                    window.controls.title.includes(String.fromCharCode(92)) &&
                    window.controls.title.includes(String.fromCharCode(10)) &&
                    window.controls.title.includes(String.fromCharCode(0x2028));
                window.webkit.messageHandlers.player.postMessage({type: 12, value: 9});
                window.webkit.messageHandlers.player.postMessage({type: 'overlayTest', value: valid ? 42 : -1});
                window.webkit.messageHandlers.player.postMessage({type: 'back', value: 3});
                window.initialWidth = window.innerWidth;
                window.initialScreenX = window.screenX;
                window.initialScreenY = window.screenY;
            """.trimIndent())
            assertTrue(delivered.await(20, TimeUnit.SECONDS), "No valid queued WebKit callback")
            assertTrue(back.await(5, TimeUnit.SECONDS), "No Back callback through the existing sink")
            assertTrue(loaded.await(10, TimeUnit.SECONDS), "No fileLoaded event for local WAV")
            NativePlayerBridge.addSubtitleUrl(handle, subtitle.toString())
            // Inspect the actual periodic playerUpdate payload in WebKit, not the JNI getter.
            NativePlayerBridge.runJavaScript(handle, """
                const checkSubtitleTracks = () => {
                    const tracks = window.playback && window.playback.subtitleTracks;
                    if (!Array.isArray(tracks) || tracks.length === 0) {
                        setTimeout(checkSubtitleTracks, 100);
                        return;
                    }
                    const valid = tracks.every((track, index) =>
                        Object.keys(track).sort().join(',') === 'forced,id,index,label,language,selected' &&
                        Number.isInteger(track.index) && track.index === index &&
                        typeof track.id === 'string' && /^\d+${'$'}/.test(track.id) &&
                        typeof track.label === 'string' && track.label.length > 0 &&
                        typeof track.language === 'string' &&
                        typeof track.selected === 'boolean' && typeof track.forced === 'boolean');
                    window.webkit.messageHandlers.player.postMessage({type:'overlaySubtitleTracks',value:valid ? 1 : -1});
                };
                checkSubtitleTracks();
            """.trimIndent())
            assertTrue(subtitleTracksDelivered.await(10, TimeUnit.SECONDS), "No subtitle array with valid schema in playerUpdate")
            NativePlayerBridge.setCursorHidden(handle, true)
            NativePlayerBridge.setCursorHidden(handle, false)
            SwingUtilities.invokeAndWait { frame?.setSize(640, 360) }
            assertTrue(resized.await(10, TimeUnit.SECONDS), "WebKit overlay did not follow Canvas resize")
            SwingUtilities.invokeAndWait {
                frame?.let { it.setLocation(it.x + 80, it.y + 60) }
            }
            NativePlayerBridge.runJavaScript(handle, """
                const checkPosition = () => {
                    if (window.screenX !== window.initialScreenX || window.screenY !== window.initialScreenY)
                        window.webkit.messageHandlers.player.postMessage({type:'overlayMoved',value:1});
                    else setTimeout(checkPosition, 100);
                };
                checkPosition();
            """.trimIndent())
            assertTrue(moved.await(10, TimeUnit.SECONDS), "WebKit toplevel did not follow Canvas movement")
            NativePlayerBridge.dispose(handle)
            NativePlayerBridge.dispose(handle)
            assertFailsWith<IllegalStateException> { NativePlayerBridge.runJavaScript(handle, "void 0") }
            handle = 0L
            val recreated = CountDownLatch(1)
            val hostLossArmed = CountDownLatch(1)
            val afterHostLoss = CountDownLatch(1)
            handle = create(drawable, source.toString(), NativePlayerEventSink { type, _ ->
                if (type == "overlayRecreated") recreated.countDown()
                if (type == "overlayHostLossArmed") hostLossArmed.countDown()
                if (type == "overlayAfterHostLoss") afterHostLoss.countDown()
            }, page.toUri().toString())
            LinuxPlayerControlsBridge.setWindowFocused(handle, true)
            NativePlayerBridge.runJavaScript(
                handle, "window.webkit.messageHandlers.player.postMessage({type:'overlayRecreated',value:1})",
            )
            assertTrue(recreated.await(20, TimeUnit.SECONDS), "No WebKit callback after recreating the overlay")
            NativePlayerBridge.runJavaScript(handle, """
                setTimeout(() => window.webkit.messageHandlers.player.postMessage({type:'overlayAfterHostLoss',value:1}), 1500);
                window.webkit.messageHandlers.player.postMessage({type:'overlayHostLossArmed',value:1});
            """.trimIndent())
            assertTrue(hostLossArmed.await(5, TimeUnit.SECONDS), "Host-loss probe was not armed")
            // Destroy the Canvas before native disposal to exercise the stale-XID race.
            // The fixture is audio-only with null output, so mpv has no video child here.
            SwingUtilities.invokeAndWait { frame?.dispose(); frame = null }
            assertFalse(afterHostLoss.await(2500, TimeUnit.MILLISECONDS), "WebKit remained active after Canvas destruction")
            NativePlayerBridge.dispose(handle)
            NativePlayerBridge.dispose(handle)
            handle = 0L
        } finally {
            if (handle != 0L) NativePlayerBridge.dispose(handle)
            SwingUtilities.invokeAndWait { frame?.dispose() }
            for (file in listOf(script, page, source, subtitle)) Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun seekThumbnailsReachHudWithoutSeekingPlaybackAndSurviveReplacementAndPendingDisposal() {
        if (!enabled() || GraphicsEnvironment.isHeadless()) return
        val directory = Files.createTempDirectory("nuvio-linux-seek-preview-")
        val first = directory.resolve("first.y4m")
        val second = directory.resolve("second.y4m")
        val host = NativePlayerHost()
        val controller = NativePlayerController(host)
        val restarts = LinkedBlockingQueue<Unit>()
        val ready = LinkedBlockingQueue<Int>()
        val images = LinkedBlockingQueue<Pair<Int, Int>>()
        val brightness = LinkedBlockingQueue<Int>()
        val error = AtomicReference<String?>(null)
        val handleField = NativePlayerController::class.java.getDeclaredField("handle").apply { isAccessible = true }
        var frame: Frame? = null
        var recreated = 0L
        fun probe(handle: Long, epoch: Int) {
            NativePlayerBridge.runJavaScript(handle, """
                const originalPreviewReady = window.nuvioSeekThumbnailReady;
                window.nuvioSeekThumbnailReady = (position, url) => {
                    originalPreviewReady(position, url);
                    const image = new Image();
                    image.onload = () => {
                        const canvas = document.createElement('canvas');
                        canvas.width = canvas.height = 1;
                        const context = canvas.getContext('2d');
                        context.drawImage(image, 0, 0, 1, 1);
                        window.webkit.messageHandlers.player.postMessage({type:'thumbnailBrightness',
                            value:context.getImageData(0, 0, 1, 1).data[0]});
                        window.webkit.messageHandlers.player.postMessage({type:'thumbnailImage$epoch', value:
                            url.startsWith('data:image/jpeg;base64,/9j/') &&
                            image.naturalWidth === 256 && image.naturalHeight === 256 ? position : -999999
                        });
                    };
                    image.onerror = () => window.webkit.messageHandlers.player.postMessage({
                        type:'thumbnailImage$epoch', value:-999999
                    });
                    image.src = url;
                };
                window.webkit.messageHandlers.player.postMessage({type:'thumbnailProbeReady',value:$epoch});
            """.trimIndent())
            assertEquals(epoch, ready.poll(15, TimeUnit.SECONDS), "Preview probe did not reach ready HUD")
        }
        fun awaitImage(epoch: Int, position: Int): Int {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (true) {
                val result = images.poll((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                assertTrue(result != null, "No JPEG preview at $position in source generation $epoch")
                assertEquals(epoch, result.first, "Old-source preview reached the new HUD")
                assertTrue(result.second != -999999, "Invalid JPEG data URL or preview dimensions")
                val pixel = brightness.poll(5, TimeUnit.SECONDS)
                assertTrue(pixel != null, "No decoded preview pixels")
                if (result.second == position) return pixel
            }
        }
        try {
            writeLocalVideo(first, firstLuma = 32, lumaStep = 5)
            writeLocalVideo(second, firstLuma = 200)
            SwingUtilities.invokeAndWait {
                frame = videoFrame(host)
                controller.setControlCallbacks({ false }, { type, value ->
                    when {
                        type == "playbackRestart" -> restarts.add(Unit)
                        type == "thumbnailProbeReady" -> ready.add(value.toInt())
                        type == "thumbnailBrightness" -> brightness.add(value.toInt())
                        type.startsWith("thumbnailImage") ->
                            images.add(type.removePrefix("thumbnailImage").toInt() to value.toInt())
                    }
                    false
                }, { false }, { false })
                attachLocalVideo(controller, first, error)
            }
            assertTrue(restarts.poll(20, TimeUnit.SECONDS) != null, "No playback restart: ${error.get()}")
            await { handleField.getLong(controller) != 0L }
            val original = handleField.getLong(controller)
            probe(original, 1)
            val initialPosition = NativePlayerBridge.positionMs(original)
            for (position in listOf(0, 1500, 2900)) {
                // Exercise HUD -> native sink -> shared Kotlin controller -> JNI as well
                // as the returning native -> WebKit callback; no network or ffmpeg fixture.
                NativePlayerBridge.runJavaScript(original, """
                    window.webkit.messageHandlers.player.postMessage({type:'seekThumbnail',value:$position});
                """.trimIndent())
                val pixel = awaitImage(1, position)
                val expectedPixel = ((32 + (position / 100) * 5 - 16) * 255 / 219).coerceIn(0, 255)
                assertTrue(kotlin.math.abs(pixel - expectedPixel) < 12,
                    "Wrong frame at $position: decoded brightness $pixel, expected $expectedPixel")
                assertTrue(kotlin.math.abs(NativePlayerBridge.positionMs(original) - initialPosition) < 150,
                    "Preview moved the paused main playhead")
                assertTrue(NativePlayerBridge.isPaused(original))
            }
            // At EOF a keyframe seek may have no frame (same timestamp-only fallback as Windows).
            // An out-of-range request must remain safe and not prevent a subsequent valid request.
            for (boundary in listOf(3000, -100)) {
                NativePlayerBridge.requestSeekThumbnail(original, boundary.toLong())
                val optional = images.poll(4, TimeUnit.SECONDS)
                if (optional != null) {
                    assertEquals(1 to boundary, optional, "Invalid boundary preview response")
                    assertTrue(brightness.poll(5, TimeUnit.SECONDS) != null)
                }
                assertTrue(NativePlayerBridge.isPaused(original))
                assertTrue(kotlin.math.abs(NativePlayerBridge.positionMs(original) - initialPosition) < 150)
            }
            SwingUtilities.invokeAndWait {
                repeat(100) { NativePlayerBridge.requestSeekThumbnail(original, (it * 27).toLong()) }
                NativePlayerBridge.requestSeekThumbnail(original, 2200)
            }
            val rapidPixel = awaitImage(1, 2200)
            val rapidExpected = (32 + 22 * 5 - 16) * 255 / 219
            assertTrue(kotlin.math.abs(rapidPixel - rapidExpected) < 12,
                "Newest timestamp carried wrong pixels: $rapidPixel, expected $rapidExpected")
            assertEquals(null, images.poll(500, TimeUnit.MILLISECONDS), "Older result overwrote latest preview")
            assertTrue(kotlin.math.abs(NativePlayerBridge.positionMs(original) - initialPosition) < 150)
            // Check the main player also keeps advancing normally during a preview.
            NativePlayerBridge.setPaused(original, false)
            val playingStart = NativePlayerBridge.positionMs(original)
            val startedAt = System.nanoTime()
            NativePlayerBridge.requestSeekThumbnail(original, 2600)
            awaitImage(1, 2600)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue(NativePlayerBridge.positionMs(original) <= playingStart + elapsedMs + 300,
                "Preview seek disturbed advancing main playback")
            await { NativePlayerBridge.positionMs(original) > playingStart + 50 }
            assertFalse(NativePlayerBridge.isPaused(original))
            NativePlayerBridge.setPaused(original, true)
            // Replace with a request still queued/in flight. New player owns a new worker,
            // WebView, generation and source; the old handle becomes a safe no-op.
            SwingUtilities.invokeAndWait {
                NativePlayerBridge.requestSeekThumbnail(original, 1700)
                attachLocalVideo(controller, second, error)
            }
            await { handleField.getLong(controller).let { it != 0L && it != original } }
            val replacement = handleField.getLong(controller)
            images.clear()
            brightness.clear()
            NativePlayerBridge.requestSeekThumbnail(original, 100)
            probe(replacement, 2)
            NativePlayerBridge.requestSeekThumbnail(replacement, 1000)
            assertTrue(awaitImage(2, 1000) > 200, "Replacement preview used old-source pixels")
            SwingUtilities.invokeAndWait {
                NativePlayerBridge.requestSeekThumbnail(replacement, 2000)
                controller.dispose() // Pending decoder must join before main X11 teardown.
            }
            NativePlayerBridge.requestSeekThumbnail(replacement, 100)
            NativePlayerBridge.dispose(replacement)
            images.clear()
            brightness.clear()
            var drawable = 0L
            SwingUtilities.invokeAndWait { drawable = LinuxAwtViewResolver.resolveNativeViewPointer(host) }
            recreated = create(drawable, first.toString(), NativePlayerEventSink { type, value ->
                if (type == "thumbnailProbeReady") ready.add(value.toInt())
                if (type == "thumbnailBrightness") brightness.add(value.toInt())
                if (type.startsWith("thumbnailImage"))
                    images.add(type.removePrefix("thumbnailImage").toInt() to value.toInt())
            }, NativePlayerBridge.controlsPageUrl)
            probe(recreated, 3)
            NativePlayerBridge.requestSeekThumbnail(recreated, 1500)
            assertTrue(awaitImage(3, 1500) in 95..118, "Recreated player used stale source/frame pixels")
            NativePlayerBridge.requestSeekThumbnail(recreated, 2400)
            NativePlayerBridge.dispose(recreated)
            NativePlayerBridge.requestSeekThumbnail(recreated, 0)
            NativePlayerBridge.dispose(recreated)
            recreated = 0L
        } finally {
            if (recreated != 0L) NativePlayerBridge.dispose(recreated)
            SwingUtilities.invokeAndWait { controller.dispose(); frame?.dispose() }
            Files.deleteIfExists(first)
            Files.deleteIfExists(second)
            Files.deleteIfExists(directory)
        }
    }

    // The standalone target compiles the same worker with phase gates; the JNI
    // library has no hooks. Every returned image, including intermediate rapid
    // results, is decoded and checked instead of trusting its timestamp label.
    private class ThumbnailProbe(source: String) : AutoCloseable {
        private val lines = LinkedBlockingQueue<String>()
        private val failure = AtomicReference<Throwable?>(null)
        private val process: Process
        private val reader: Thread
        private val input: java.io.BufferedWriter
        var images = 0
            private set

        init {
            val build = listOf(Path.of("build/native/linux"), Path.of("composeApp/build/native/linux"))
                .first { Files.exists(it.resolve("CMakeCache.txt")) }.toAbsolutePath()
            val compile = ProcessBuilder("cmake", "--build", build.toString(), "--target",
                "linux_seek_thumbnail_probe").redirectErrorStream(true).start()
            val output = compile.inputStream.bufferedReader().readText()
            check(compile.waitFor() == 0) { output }
            process = ProcessBuilder(build.resolve("linux_seek_thumbnail_probe").toString(), source)
                .redirectErrorStream(true).start()
            input = process.outputStream.bufferedWriter()
            reader = Thread({
                process.inputStream.bufferedReader().useLines { stream ->
                    stream.forEach { line ->
                        try {
                            if (line.startsWith("IMAGE ")) {
                                val parts = line.split(' ', limit = 3)
                                val position = parts[1].toLong()
                                assertTrue(parts[2].startsWith("data:image/jpeg;base64,/9j/"))
                                val bytes = Base64.getDecoder().decode(parts[2].substringAfter(','))
                                val image = ImageIO.read(ByteArrayInputStream(bytes))
                                assertEquals(256, image.width)
                                assertEquals(256, image.height)
                                val pixel = image.getRGB(0, 0) shr 16 and 255
                                val expected = ((32 + (position / 100) * 5 - 16) * 255 / 219).toInt()
                                assertTrue(kotlin.math.abs(pixel - expected) < 12,
                                    "Mislabeled frame at $position: $pixel, expected $expected")
                                images++
                                lines.add("IMAGE $position $pixel")
                            } else lines.add(line)
                        } catch (error: Throwable) { failure.compareAndSet(null, error) }
                    }
                }
            }, "Linux thumbnail probe reader").apply { start() }
        }

        fun send(command: String) { input.write(command); input.newLine(); input.flush() }
        fun await(prefix: String, seconds: Long = 15): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (System.nanoTime() < deadline) {
                failure.get()?.let { throw AssertionError("Preview frame validation failed", it) }
                val line = lines.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (line.startsWith(prefix)) return line
            }
            error("No probe result '$prefix'; process alive=${process.isAlive}")
        }
        fun open() { send("OPEN"); await("OPENED") }
        fun image(position: Long) = await("IMAGE $position ")
        fun gate(phase: String) { send("GATE $phase"); await("GATED $phase") }
        fun dispose(): Long {
            send("CLOSE")
            val micros = await("CLOSED ").substringAfter(' ').toLong()
            assertTrue(micros < 5_000_000, "Preview disposal exceeded 5 seconds: $micros us")
            return micros
        }
        override fun close() {
            try {
                send("QUIT")
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Probe worker failed to shut down")
                assertEquals(0, process.exitValue())
            } finally {
                if (process.isAlive) { process.destroyForcibly(); process.waitFor() }
                input.close()
                reader.join(5000)
                assertFalse(reader.isAlive, "Probe reader leaked")
            }
            failure.get()?.let { throw AssertionError("Preview frame validation failed", it) }
        }
    }

    private fun withThumbnailProbe(test: (ThumbnailProbe) -> Unit) {
        if (!enabled()) return
        val source = Files.createTempFile("nuvio-linux-thumbnail-stress-", ".y4m")
        try {
            writeLocalVideo(source, firstLuma = 32, lumaStep = 5)
            ThumbnailProbe(source.toString()).use(test)
        } finally { Files.deleteIfExists(source) }
    }

    @Test
    fun thumbnailFirstNonzeroFrameSurvivesOneHundredFreshDecoders() = withThumbnailProbe { probe ->
        var maximum = 0L
        repeat(100) {
            probe.open()
            probe.send("REQUEST 1500")
            probe.image(1500)
            maximum = maxOf(maximum, probe.dispose())
        }
        println("Thumbnail fresh-decoder stress: iterations=100 wrongFrames=0 maxDisposeUs=$maximum")
    }

    @Test
    fun thumbnailRapidRequestsValidateEveryDeliveredFrame() = withThumbnailProbe { probe ->
        probe.open()
        probe.send("REQUEST 0")
        probe.image(0)
        repeat(20) { batch ->
            // Force work to begin before superseding it, rather than merely
            // coalescing an entirely idle burst on the JNI caller thread.
            probe.gate("seek-entered")
            probe.send("REQUEST 1000")
            probe.await("PHASE seek-entered ")
            repeat(100) { i -> probe.send("REQUEST ${((i * 7 + batch * 11) % 30) * 100}") }
            probe.send("REQUEST 1500")
            probe.send("RELEASE")
            probe.image(1500)
        }
        probe.dispose()
        println("Thumbnail rapid stress: requests=2041 deliveredFinals=20 wrongFrames=0")
    }

    @Test
    fun thumbnailSupersededSeekCannotSatisfyNewestRequest() = withThumbnailProbe { probe ->
        probe.open()
        repeat(20) {
            probe.gate("seek-entered")
            probe.send("REQUEST 1000")
            probe.await("PHASE seek-entered ")
            probe.send("REQUEST 500")
            probe.send("REQUEST 1500")
            probe.send("RELEASE")
            probe.image(1500)
        }
        probe.dispose()
    }

    @Test
    fun thumbnailShutdownAtObservedPhasesJoinsWorker() = withThumbnailProbe { probe ->
        var maximum = 0L
        val phases = listOf("initializing", "load-issued", "waiting-file", "seek-issued",
            "seek-entered", "seek-ready", "capture-issued", "readback", "delivery")
        for (phase in phases) repeat(10) {
            probe.open()
            probe.gate(phase)
            probe.send("REQUEST 1500")
            probe.await("PHASE $phase ")
            maximum = maxOf(maximum, probe.dispose())
            probe.send("REQUEST 1500") // disposed helper cannot be resurrected
        }
        println("Thumbnail phase shutdown: phases=$phases cycles=90 maxDisposeUs=$maximum")
    }

    @Test
    fun thumbnailFailedLoadRetriesOnlyWhenRequestedAgain() {
        if (!enabled()) return
        val directory = Files.createTempDirectory("nuvio-linux-thumbnail-retry-")
        val source = directory.resolve("later.y4m")
        try {
            ThumbnailProbe(source.toString()).use { probe ->
                probe.open()
                probe.send("REQUEST 1500")
                probe.await("PHASE decoder-closed ")
                writeLocalVideo(source, firstLuma = 32, lumaStep = 5)
                probe.send("REQUEST 1500") // same helper/worker; new context after failure
                probe.image(1500)
                probe.dispose()
            }
            println("Thumbnail failed-load recovery: sameHelperRecovered=true")
        } finally { Files.deleteIfExists(source); Files.deleteIfExists(directory) }
    }

    @Test
    fun thumbnailShutdownInterruptsEnteredHttpLoad() {
        if (!enabled()) return
        val source = Files.createTempFile("nuvio-linux-thumbnail-stall-", ".y4m")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = java.util.concurrent.Executors.newCachedThreadPool()
        val arrived = AtomicReference(CountDownLatch(1))
        val release = AtomicReference(CountDownLatch(1))
        val completed = AtomicReference(CountDownLatch(1))
        server.executor = executor
        var maximum = 0L
        try {
            writeLocalVideo(source, firstLuma = 32, lumaStep = 5)
            val bytes = Files.readAllBytes(source)
            server.createContext("/fixture.y4m") { exchange ->
                val done = completed.get()
                try {
                    arrived.get().countDown()
                    release.get().await(20, TimeUnit.SECONDS) // no headers until cancellation measured
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } catch (_: java.io.IOException) {
                    // Expected when the cancelled libmpv connection has gone away.
                } finally { exchange.close(); done.countDown() }
            }
            server.start()
            ThumbnailProbe("http://127.0.0.1:${server.address.port}/fixture.y4m").use { probe ->
                repeat(10) {
                    arrived.set(CountDownLatch(1))
                    release.set(CountDownLatch(1))
                    completed.set(CountDownLatch(1))
                    probe.open()
                    probe.send("REQUEST 1500")
                    assertTrue(arrived.get().await(5, TimeUnit.SECONDS), "HTTP load did not enter")
                    maximum = maxOf(maximum, probe.dispose())
                    release.get().countDown()
                    assertTrue(completed.get().await(5, TimeUnit.SECONDS))
                }
            }
            println("Thumbnail HTTP shutdown: enteredLoads=10 maxDisposeUs=$maximum")
        } finally {
            release.get().countDown()
            server.stop(0)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "HTTP fixture executor leaked")
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun slowThumbnailLoadRecoversOnTheSameNativePlayer() {
        if (!enabled() || GraphicsEnvironment.isHeadless()) return
        val source = Files.createTempFile("nuvio-linux-thumbnail-http-", ".y4m")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val stalled = java.util.concurrent.atomic.AtomicBoolean(false)
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ready = LinkedBlockingQueue<Unit>()
        val images = LinkedBlockingQueue<Int>()
        var handle = 0L
        var frame: Frame? = null
        try {
            writeLocalVideo(source, firstLuma = 32, lumaStep = 5)
            val bytes = Files.readAllBytes(source)
            server.createContext("/fixture.y4m") { exchange ->
                try {
                    if (stalled.get()) { arrived.countDown(); release.await(20, TimeUnit.SECONDS) }
                    val start = exchange.requestHeaders.getFirst("Range")?.substringAfter("bytes=")
                        ?.substringBefore('-')?.toIntOrNull() ?: 0
                    exchange.responseHeaders.add("Accept-Ranges", "bytes")
                    exchange.responseHeaders.add("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
                    exchange.sendResponseHeaders(206, (bytes.size - start).toLong())
                    exchange.responseBody.use { it.write(bytes, start, bytes.size - start) }
                } finally { exchange.close() }
            }
            server.start()
            val host = NativePlayerHost()
            var drawable = 0L
            SwingUtilities.invokeAndWait {
                frame = videoFrame(host)
                drawable = LinuxAwtViewResolver.resolveNativeViewPointer(host)
            }
            handle = create(drawable, "http://127.0.0.1:${server.address.port}/fixture.y4m",
                NativePlayerEventSink { type, value ->
                    if (type == "thumbnailHttpReady") ready.add(Unit)
                    if (type == "thumbnailHttpPixel") images.add(value.toInt())
                }, NativePlayerBridge.controlsPageUrl)
            NativePlayerBridge.runJavaScript(handle, """
                window.nuvioSeekThumbnailReady = (position, url) => {
                    const image = new Image();
                    image.onload = () => {
                        const c = document.createElement('canvas'); c.width = c.height = 1;
                        const ctx = c.getContext('2d'); ctx.drawImage(image, 0, 0, 1, 1);
                        window.webkit.messageHandlers.player.postMessage({type:'thumbnailHttpPixel',
                            value: position === 1500 ? ctx.getImageData(0,0,1,1).data[0] : -999});
                    }; image.src = url;
                };
                window.webkit.messageHandlers.player.postMessage({type:'thumbnailHttpReady',value:0});
            """.trimIndent())
            assertTrue(ready.poll(15, TimeUnit.SECONDS) != null)
            await { NativePlayerBridge.durationMs(handle) > 0 }
            stalled.set(true)
            NativePlayerBridge.requestSeekThumbnail(handle, 0)
            assertTrue(arrived.await(5, TimeUnit.SECONDS), "Preview never opened the local HTTP source")
            assertEquals(null, images.poll(9, TimeUnit.SECONDS), "Fixture was not stalled past eight seconds")
            assertTrue(NativePlayerBridge.isPaused(handle))
            NativePlayerBridge.requestSeekThumbnail(handle, 1500)
            release.countDown()
            val pixel = images.poll(15, TimeUnit.SECONDS)
            assertTrue(pixel != null && pixel in 95..118, "Slow preview failed to recover: $pixel")
            assertTrue(NativePlayerBridge.isPaused(handle))
            println("Thumbnail slow load: stalled=9s samePlayerRecovered=true")
        } finally {
            release.countDown()
            if (handle != 0L) NativePlayerBridge.dispose(handle)
            SwingUtilities.invokeAndWait { frame?.dispose() }
            server.stop(0)
            Files.deleteIfExists(source)
        }
    }

    private fun videoFrame(host: NativePlayerHost) = Frame("Nuvio Linux video lifecycle test").apply {
        isAutoRequestFocus = false
        focusableWindowState = false
        add(host)
        setSize(320, 180)
        isVisible = true
    }

    private fun attachLocalVideo(
        controller: NativePlayerController,
        source: java.nio.file.Path,
        error: AtomicReference<String?>,
    ) = controller.attach(
        sourceUrl = source.toString(), sourceAudioUrl = null, sourceHeaders = emptyMap(),
        playWhenReady = false, initialPositionMs = 0L,
        nvidiaRtxSuperResolutionEnabled = false, nvidiaRtxHdrEnabled = false,
        onError = { error.set(it ?: "Native attach failed") },
    )

    private fun writeLocalVideo(source: java.nio.file.Path, firstLuma: Int = 128, lumaStep: Int = 0) {
        Files.newOutputStream(source).use { output ->
            output.write("YUV4MPEG2 W64 H64 F10:1 Ip A1:1 C420jpeg\n".toByteArray())
            val pixels = ByteArray(64 * 64 * 3 / 2) { 128.toByte() }
            repeat(30) { frame ->
                pixels.fill((firstLuma + frame * lumaStep).toByte(), 0, 64 * 64)
                output.write("FRAME\n".toByteArray())
                output.write(pixels)
            }
        }
    }

    private fun silentWav(): ByteArray {
        val pcmBytes = 8000 * 2 * 5
        val wav = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray())
        wav.putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
        wav.put("data".toByteArray()).putInt(pcmBytes)
        return wav.array()
    }

    private fun create(
        drawable: Long,
        source: String,
        sink: NativePlayerEventSink = NativePlayerEventSink { _, _ -> },
        controlsPageUrl: String = "",
    ) =
        NativePlayerBridge.create(
            hostViewPtr = drawable,
            sourceUrl = source,
            sourceAudioUrl = null,
            headerLines = arrayOf("X-Nuvio-Test: value, with commas"),
            playWhenReady = false,
            initialPositionMs = 1000L,
            initialProgressFraction = 0.0,
            controlsPageUrl = controlsPageUrl,
            nvidiaRtxSuperResolutionEnabled = false,
            nvidiaRtxHdrEnabled = false,
            isAnimeContent = false,
            animeSvpFilter = null,
            // Keep null-output buffering below the assertions' tolerance. Its default 200 ms
            // queue otherwise offsets the paused audio-only playhead after a seek.
            // 50 ms also leaves room for null AO's 256-sample output chunks at 8 kHz.
            extraMpvOptions = arrayOf("ao=null", "audio-display=no", "audio-buffer=0.05", "ao-null-buffer=0.05"),
            eventSink = sink,
        )

    private fun await(state: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "Timed out waiting for libmpv state: ${state()}")
            Thread.sleep(20)
        }
    }
}
