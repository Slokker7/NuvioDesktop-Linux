package com.nuvio.app.features.player.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import com.nuvio.app.features.player.DesktopBufferPreset
import com.nuvio.app.features.player.DesktopColorProfile
import com.nuvio.app.features.player.DesktopMpvConfigMode
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlatformPlayerSurface
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Effective properties from the private libmpv; the observer never changes player state. */
class LinuxBaseConfigurationTest {
    private fun enabled() = DesktopHostOs.current == DesktopHostOs.LINUX &&
        System.getProperty("nuvio.linux.nativeSmokeTest") == "true" && !GraphicsEnvironment.isHeadless()

    @Test
    fun nativeBaseDefaultsRespectEveryCustomMode() {
        if (!enabled()) return
        Fixture().use { f ->
            for (mode in DesktopMpvConfigMode.entries) {
                f.openNative(mode, listOf("@nuvio-user:scale='bilinear'", "@nuvio-user:deband=no"))
                val v = f.values()
                val custom = mode in listOf(DesktopMpvConfigMode.Replace, DesktopMpvConfigMode.Full)
                // Off entries are excluded by the shared producer, also represented by this fixture.
                assertEquals(if (custom) "bilinear" else "spline36", v["scale"], mode.name)
                assertEquals(if (custom) "no" else "yes", v["deband"], mode.name)
                if (mode == DesktopMpvConfigMode.Full) {
                    for (key in listOf("cscale", "dscale", "dither-depth", "cache-secs", "volume-max")) {
                        assertEquals(v["default/$key"], v[key], "$mode: $key")
                    }
                } else {
                    for ((key, value) in mapOf("cscale" to "lanczos", "dscale" to "mitchell",
                        "dither" to "fruit", "dither-depth" to "10", "temporal-dither" to "yes",
                        "deband-iterations" to "2", "deband-threshold" to "35", "deband-range" to "16",
                        "deband-grain" to "0", "sigmoid-upscaling" to "yes", "correct-downscaling" to "yes",
                        "linear-downscaling" to "no", "cache" to "yes", "cache-pause" to "yes",
                        "cache-pause-initial" to "yes", "hr-seek" to "no", "volume-max" to "200")) {
                        if (value.toDoubleOrNull() != null) {
                            assertEquals(value.toDouble(), v.getValue(key).toDouble(), "$mode: $key")
                        } else assertEquals(value, v[key], "$mode: $key")
                    }
                    assertEquals(0.7, v.getValue("scale-antiring").toDouble())
                }
                f.assertIntegration(v)
                f.closePlayer()
            }
        }
    }

    @Test
    fun bufferPresetsApplyBeforeLoadAndSurviveSourceReplacementAndReopen() {
        if (!enabled()) return
        Fixture().use { f ->
            val settings = PlayerSettingsRepository
            settings.setDesktopMpvConfigMode(DesktopMpvConfigMode.Off)
            settings.setDesktopBufferPreset(DesktopBufferPreset.LowData)
            f.openController()
            f.assertBuffer("30", "15", "67108864", "16777216")
            assertEquals("1048576", f.values()["stream-buffer-size"])
            settings.setDesktopBufferPreset(DesktopBufferPreset.Resilient)
            f.controller!!.applyDesktopBufferPreset(DesktopBufferPreset.Resilient)
            f.awaitValue("cache-secs", "600")
            f.assertBuffer("600", "180", "1073741824", "134217728")
            f.openController(f.second)
            f.assertBuffer("600", "180", "1073741824", "134217728")
            f.closePlayer() // Back: destroys the controller as well as its libmpv instance.
            f.openController()
            f.assertBuffer("600", "180", "1073741824", "134217728")
            f.controller!!.setPlaybackSpeed(2f)
            f.awaitValue("cache-secs", "1200")
            settings.setDesktopBufferPreset(DesktopBufferPreset.Balanced)
            f.controller!!.applyDesktopBufferPreset(DesktopBufferPreset.Balanced)
            f.awaitValue("cache-secs", "240")
            assertEquals("1048576", f.values()["stream-buffer-size"])
            f.assertIntegration(f.values())
        }
    }

    @Test
    fun decodedSdrClassificationReachesTheExistingProfileEvent() {
        if (!enabled()) return
        Fixture().use { f ->
            f.openNative(DesktopMpvConfigMode.Off)
            assertEquals(0.0, f.videoParams.poll(10, TimeUnit.SECONDS), "No decoded SDR classification")
        }
    }

    @Test
    fun runtimeBufferModesAndMeteredPauseKeepTheirPrecedence() {
        if (!enabled()) return
        Fixture().use { f ->
            val settings = PlayerSettingsRepository
            for (mode in DesktopMpvConfigMode.entries) {
                settings.setDesktopMpvConfigMode(mode)
                settings.setDesktopBufferPreset(DesktopBufferPreset.Balanced)
                f.openController(custom = "cache-secs='7'")
                val customWins = mode in listOf(DesktopMpvConfigMode.Replace, DesktopMpvConfigMode.Full)
                assertEquals(if (customWins) 7.0 else 120.0, f.values().getValue("cache-secs").toDouble())
                settings.setDesktopBufferPreset(DesktopBufferPreset.LowData)
                f.controller!!.applyDesktopBufferPreset(DesktopBufferPreset.LowData)
                f.awaitValue("cache-secs", if (customWins) "7" else "30")
                f.controller!!.setPlaybackSpeed(2f)
                f.awaitValue("cache-secs", if (customWins) "7" else "60")
                f.closePlayer()
            }
            settings.setDesktopMpvConfigMode(DesktopMpvConfigMode.Off)
            settings.setDesktopBufferPreset(DesktopBufferPreset.Metered)
            f.openController()
            f.waitFor {
                f.controller!!.snapshot() // The existing shared paused-prefetch policy.
                f.values()["cache-secs"]?.toDouble() == 1.0
            }
            f.controller!!.play()
            f.awaitValue("cache-secs", "10")
            f.openController(f.second)
            f.assertBuffer("10", "10", "33554432", "8388608")
        }
    }

    @Test
    fun colorPresetsApplyLiveResetAndSurviveReplacementWithoutGradingHdr() {
        if (!enabled()) return
        Fixture().use { f ->
            val settings = PlayerSettingsRepository
            settings.setDesktopMpvConfigMode(DesktopMpvConfigMode.Off)
            settings.setDesktopColorProfile(DesktopColorProfile.Neutral)
            f.openController()
            f.applyProfile(false)
            f.assertGrade(0, 0, 0, 0)
            settings.setDesktopColorProfile(DesktopColorProfile.Cinematic)
            f.applyProfile(false)
            f.assertGrade(2, -6, 2, 2)
            settings.setDesktopColorProfile(DesktopColorProfile.Vivid)
            f.applyProfile(false)
            f.assertGrade(5, -4, 15, -2)
            settings.setDesktopColorContrast(9)
            settings.setDesktopColorBrightness(-8)
            settings.setDesktopColorSaturation(17)
            settings.setDesktopColorGamma(3)
            settings.setDesktopColorProfile(DesktopColorProfile.Custom)
            f.applyProfile(false)
            f.assertGrade(9, -8, 17, 3)
            f.openController(f.second)
            f.applyProfile(false)
            f.assertGrade(9, -8, 17, 3)
            f.closePlayer()
            f.openController()
            f.applyProfile(false)
            f.assertGrade(9, -8, 17, 3)
            f.applyProfile(null)
            f.assertGrade(0, 0, 0, 0)
            f.applyProfile(true)
            f.assertGrade(0, 0, 0, 0)
            settings.resetDesktopColorTuning()
            f.applyProfile(false)
            f.assertGrade(0, 0, 0, 0)
            assertEquals(DesktopColorProfile.Custom, settings.uiState.value.desktopColorProfile)
            f.assertIntegration(f.values())
        }
    }

    @Test
    fun colorProfileRefreshRespectsCustomModes() {
        if (!enabled()) return
        Fixture().use { f ->
            val settings = PlayerSettingsRepository
            settings.setDesktopColorProfile(DesktopColorProfile.Vivid)
            for (mode in DesktopMpvConfigMode.entries) {
                settings.setDesktopMpvConfigMode(mode)
                f.openController(custom = "contrast='18'")
                f.applyProfile(false)
                when (mode) {
                    DesktopMpvConfigMode.Off, DesktopMpvConfigMode.Add -> f.assertGrade(5, -4, 15, -2)
                    DesktopMpvConfigMode.Replace -> f.assertGrade(18, -4, 15, -2)
                    DesktopMpvConfigMode.Full -> f.assertGrade(18, 0, 0, 0)
                }
                f.closePlayer()
            }
        }
    }

    @Test
    fun invalidBaseOptionsKeepPlaybackAndRequiredIntegrationUsable() {
        if (!enabled()) return
        Fixture().use { f ->
            f.openNative(DesktopMpvConfigMode.Replace, listOf(
                "@nuvio-user:scale=not-a-scaler", "@nuvio-user:deband-iterations=invalid",
                "@nuvio-user:dscale=\"bilinear\""))
            val v = f.values()
            assertEquals("spline36", v["scale"])
            assertEquals("2", v["deband-iterations"])
            assertEquals("bilinear", v["dscale"])
            NativePlayerBridge.setMpvProperty(f.handle, "scale", "not-a-scaler")
            NativePlayerBridge.setPaused(f.handle, false)
            f.waitFor { NativePlayerBridge.positionMs(f.handle) > 300 }
            NativePlayerBridge.seekTo(f.handle, 2000)
            f.waitFor { NativePlayerBridge.positionMs(f.handle) >= 1900 }
            f.assertIntegration(f.values())
        }
    }

    @Test
    fun realPlayerSurfaceCollectsSettingsAndReappliesProfilesAcrossSources() {
        if (!enabled()) return
        Fixture().use { f ->
            val settings = PlayerSettingsRepository
            settings.setDesktopMpvConfigMode(DesktopMpvConfigMode.Off)
            settings.setDesktopColorProfile(DesktopColorProfile.Vivid)
            val source = mutableStateOf(f.first.toString())
            fun openSurface() {
                f.prepareControllerOptions()
                SwingUtilities.invokeAndWait {
                    f.frame?.dispose()
                    f.frame = ComposeWindow().apply {
                        title = "Nuvio Linux real player surface test"
                        setSize(640, 480)
                        setContent {
                            PlatformPlayerSurface(sourceUrl = source.value, modifier = Modifier.fillMaxSize(),
                                playWhenReady = false,
                                onControllerReady = { f.controller = it as NativePlayerController },
                                onSnapshot = {}, onError = { f.error.set(it) })
                        }
                        isVisible = true
                    }
                }
            }
            openSurface()
            // No direct call to the profile helper: this exercises the actual engine's collector.
            f.assertGrade(5, -4, 15, -2)
            settings.setDesktopColorProfile(DesktopColorProfile.Cinematic)
            f.assertGrade(2, -6, 2, 2)
            settings.setDesktopBufferPreset(DesktopBufferPreset.LowData)
            f.awaitValue("cache-secs", "30")
            SwingUtilities.invokeAndWait { source.value = f.second.toString() }
            f.awaitValue("path", f.second.toString())
            f.assertGrade(2, -6, 2, 2)
            f.closePlayer()
            openSurface()
            f.assertGrade(2, -6, 2, 2)
            settings.setDesktopColorProfile(DesktopColorProfile.Custom)
            settings.setDesktopColorContrast(12)
            f.awaitValue("contrast", "12")
            settings.resetDesktopColorTuning()
            f.assertGrade(0, 0, 0, 0)
            f.assertIntegration(f.values())
        }
    }

    internal class Fixture : AutoCloseable {
        val directory: Path = Files.createTempDirectory("nuvio-linux-base-config-")
        val first = directory.resolve("first.y4m")
        val second = directory.resolve("second.y4m")
        val host = NativePlayerHost()
        var frame: Frame? = null
        var handle = 0L
        var controller: NativePlayerController? = null
        val videoParams = LinkedBlockingQueue<Double>()
        val error = AtomicReference<String?>(null)
        private val previous = PlayerSettingsRepository.uiState.value
        private var serial = 0
        private lateinit var report: Path

        init {
            writeVideo(first, 110)
            writeVideo(second, 150)
            SwingUtilities.invokeAndWait {
                frame = Frame("Nuvio Linux base configuration test").apply {
                    add(host); setSize(480, 320); isVisible = true
                }
            }
            previous.desktopMpvPropertyOverrides.keys.forEach {
                PlayerSettingsRepository.setDesktopMpvPropertyOverride(it, null)
            }
        }

        fun openNative(mode: DesktopMpvConfigMode, custom: List<String> = emptyList()) {
            val script = observer()
            var drawable = 0L
            SwingUtilities.invokeAndWait { drawable = LinuxAwtViewResolver.resolveNativeViewPointer(host) }
            handle = NativePlayerBridge.create(drawable, first.toString(), null, emptyArray(), false,
                0L, 0.0, "", false, false, false, null,
                (listOf("ao=null", "scripts=$script", "@nuvio-config-mode=${mode.name.lowercase()}") +
                    if (mode == DesktopMpvConfigMode.Off) emptyList() else custom).toTypedArray(),
                NativePlayerEventSink { type, value -> if (type == "videoParams") videoParams.offer(value) })
            values()
        }

        fun openController(source: Path = first, custom: String = "") {
            prepareControllerOptions(custom)
            if (controller == null) controller = NativePlayerController(host)
            SwingUtilities.invokeAndWait {
                controller!!.attach(sourceUrl = source.toString(), sourceAudioUrl = null,
                    sourceHeaders = emptyMap(), playWhenReady = false, initialPositionMs = 0L,
                    nvidiaRtxSuperResolutionEnabled = false, nvidiaRtxHdrEnabled = false,
                    onError = { error.set(it ?: "Native attach failed") }, enableUserMpvOptions = true)
            }
            values()
        }

        fun prepareControllerOptions(custom: String = "") {
            val script = observer()
            val settings = PlayerSettingsRepository
            settings.setDesktopMpvPropertyOverride("scripts", script.toString())
            settings.setDesktopMpvPropertyOverride("ao", "null")
            settings.setDesktopCustomMpvOptions("$custom\nscripts=$script\nao=null")
        }

        fun values(): Map<String, String> {
            waitFor { Files.exists(report) }
            return Json.parseToJsonElement(Files.readString(report)).jsonObject
                .mapValues { it.value.jsonPrimitive.content }
        }

        fun awaitValue(key: String, expected: String) = waitFor {
            val value = values()[key]
            value == expected || (expected.toDoubleOrNull() != null && value?.toDoubleOrNull() == expected.toDouble())
        }

        fun screenshot(target: Path) {
            Files.writeString(Path.of(report.toString() + ".screenshot"), target.toString())
            waitFor { Files.exists(target) }
        }

        fun applyProfile(isHdr: Boolean?) =
            controller!!.applyLinuxBaseVideoProfile(PlayerSettingsRepository.uiState.value, isHdr)

        fun assertGrade(contrast: Int, brightness: Int, saturation: Int, gamma: Int) {
            val expected = mapOf("contrast" to contrast, "brightness" to brightness,
                "saturation" to saturation, "gamma" to gamma)
            waitFor { values().let { v -> expected.all { (k, n) -> v[k]?.toDoubleOrNull() == n.toDouble() } } }
        }

        fun assertBuffer(cache: String, readahead: String, max: String, back: String) {
            val v = values()
            assertEquals(cache.toDouble(), v.getValue("cache-secs").toDouble())
            assertEquals(readahead.toDouble(), v.getValue("demuxer-readahead-secs").toDouble())
            assertEquals(max, v["demuxer-max-bytes"])
            assertEquals(back, v["demuxer-max-back-bytes"])
        }

        fun assertIntegration(v: Map<String, String>) {
            for ((key, value) in mapOf("hwdec" to "auto", "vo" to "gpu-next", "gpu-api" to "vulkan",
                "gpu-context" to "x11vk", "config" to "no")) assertEquals(value, v[key], key)
        }

        private fun observer(): Path {
            report = directory.resolve("report-${++serial}.json")
            return directory.resolve("observer-$serial.lua").also { script ->
                Files.writeString(script, """
                    local utils = require 'mp.utils'
                    local report = ${Json.encodeToString(report.toString())}
                    local function observe()
                        local values = {}
                        values.path = mp.get_property('path')
                        values.passes = utils.format_json(mp.get_property_native('vo-passes') or {})
                        local request = io.open(report .. '.screenshot', 'r')
                        if request then
                            local target = request:read('*a'); request:close()
                            os.remove(report .. '.screenshot')
                            mp.commandv('screenshot-to-file', target, 'window')
                        end
                        for key in string.gmatch('scale cscale dscale scale-antiring dither dither-depth temporal-dither deband deband-iterations deband-threshold deband-range deband-grain sigmoid-upscaling correct-downscaling linear-downscaling cache cache-pause cache-pause-initial cache-pause-wait cache-secs demuxer-readahead-secs demuxer-max-bytes demuxer-max-back-bytes stream-buffer-size hr-seek volume-max hwdec vo gpu-api gpu-context config contrast brightness saturation gamma speed glsl-shaders vf target-trc tone-mapping mpv-version frame-drop-count decoder-frame-drop-count time-pos video-params/w video-params/h', '%S+') do
                            values[key] = mp.get_property(key)
                        end
                        for key in string.gmatch('cscale dscale dither-depth cache-secs volume-max', '%S+') do
                            values['default/' .. key] = mp.get_property('option-info/' .. key .. '/default-value')
                        end
                        local file = assert(io.open(report .. '.new', 'w'))
                        file:write(utils.format_json(values)); file:close()
                        assert(os.rename(report .. '.new', report))
                    end
                    mp.register_event('file-loaded', function()
                        observe()
                        mp.add_periodic_timer(0.05, observe)
                    end)
                """.trimIndent())
            }
        }

        private fun writeVideo(path: Path, luma: Int) = Files.newOutputStream(path).use { out ->
            out.write("YUV4MPEG2 W64 H64 F10:1 Ip A1:1 C420jpeg\n".toByteArray())
            val pixels = ByteArray(64 * 64 * 3 / 2) { 128.toByte() }
            pixels.fill(luma.toByte(), 0, 64 * 64)
            repeat(120) { out.write("FRAME\n".toByteArray()); out.write(pixels) }
        }

        fun waitFor(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!condition()) {
                assertEquals(null, error.get())
                assertTrue(System.nanoTime() < deadline, "Timed out waiting for private libmpv")
                Thread.sleep(25)
            }
        }

        fun closePlayer() {
            SwingUtilities.invokeAndWait { controller?.dispose() }
            controller = null
            if (handle != 0L) NativePlayerBridge.dispose(handle)
            handle = 0L
        }

        override fun close() {
            closePlayer()
            SwingUtilities.invokeAndWait { frame?.dispose() }
            val settings = PlayerSettingsRepository
            settings.uiState.value.desktopMpvPropertyOverrides.keys.forEach {
                settings.setDesktopMpvPropertyOverride(it, null)
            }
            previous.desktopMpvPropertyOverrides.forEach { (k, v) -> settings.setDesktopMpvPropertyOverride(k, v) }
            settings.setDesktopCustomMpvOptions(previous.desktopCustomMpvOptions)
            settings.setDesktopMpvConfigMode(previous.desktopMpvConfigMode)
            settings.setDesktopBufferPreset(previous.desktopBufferPreset)
            settings.setDesktopColorProfile(previous.desktopColorProfile)
            settings.setDesktopColorContrast(previous.desktopColorContrast)
            settings.setDesktopColorBrightness(previous.desktopColorBrightness)
            settings.setDesktopColorSaturation(previous.desktopColorSaturation)
            settings.setDesktopColorGamma(previous.desktopColorGamma)
            directory.toFile().deleteRecursively()
        }
    }
}
