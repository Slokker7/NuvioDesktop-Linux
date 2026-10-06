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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        assertEquals("[]", NativePlayerBridge.subtitleTracksJson(0L))
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
