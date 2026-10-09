package com.nuvio.app.features.player.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import com.nuvio.app.features.player.*
import java.awt.GraphicsEnvironment
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxShaderProfileTest {
    private fun enabled() = DesktopHostOs.current == DesktopHostOs.LINUX &&
        System.getProperty("nuvio.linux.nativeSmokeTest") == "true" && !GraphicsEnvironment.isHeadless()

    @Test
    fun realSurfaceAppliesSelectedShaders() {
        if (!enabled()) return
        val settings = PlayerSettingsRepository
        val previous = settings.uiState.value
        try {
            LinuxBaseConfigurationTest.Fixture().use { f ->
                settings.setDesktopMpvConfigMode(DesktopMpvConfigMode.Off)
                settings.setDesktopAnimeMode(DesktopAnimeMode.ModeAFast)
                settings.setDesktopAnimeModeAutoEnabled(true)
                settings.setDesktopAnimeSvpEnabled(true) // Linux must still bypass the SVP startup/profile path.
                settings.clearDesktopAnimeSessionState()
                val source = mutableStateOf(f.first.toString())
                val anime = mutableStateOf(true)
                fun open() {
                    f.prepareControllerOptions()
                    SwingUtilities.invokeAndWait {
                        f.frame?.dispose()
                        f.frame = ComposeWindow().apply {
                            setSize(640, 480)
                            setContent {
                                PlatformPlayerSurface(sourceUrl = source.value, modifier = Modifier.fillMaxSize(),
                                    isAnimeContent = anime.value, playWhenReady = false,
                                    onControllerReady = { f.controller = it as NativePlayerController },
                                    onSnapshot = {}, onError = { f.error.set(it) })
                            }
                            isVisible = true
                        }
                    }
                }
                open()
                f.waitFor { f.values()["glsl-shaders"]?.contains("Anime4K_Restore_CNN_M") == true }
                println("SURFACE Anime4K=" + f.values()["glsl-shaders"])
                SwingUtilities.invokeAndWait { anime.value = false }
                f.awaitValue("glsl-shaders", "")
                settings.setDesktopAnimeSessionOverride(DesktopAnimeSessionOverride(DesktopAnimeMode.ModeBFast))
                f.waitFor { f.values()["glsl-shaders"]?.contains("Restore_CNN_Soft_M") == true }
                SwingUtilities.invokeAndWait { source.value = f.second.toString() }
                f.awaitValue("path", f.second.toString())
                f.waitFor { f.values()["glsl-shaders"]?.contains("Restore_CNN_Soft_M") == true }
                settings.clearDesktopAnimeSessionState()
                f.awaitValue("glsl-shaders", "")
                val shader = f.directory.resolve("test shader 日本語.glsl")
                Files.writeString(shader, diagnosticShader)
                settings.setDesktopCustomShaderPaths(shader.toString())
                settings.setDesktopCustomShaderSelectedPath(shader.toString())
                settings.setDesktopAnimeMode(DesktopAnimeMode.CustomShader)
                SwingUtilities.invokeAndWait { anime.value = true }
                f.awaitValue("glsl-shaders", shader.toString())
                println("SURFACE custom=" + f.values()["glsl-shaders"])
                SwingUtilities.invokeAndWait { source.value = f.first.toString() }
                f.awaitValue("path", f.first.toString())
                f.awaitValue("glsl-shaders", shader.toString())
                f.closePlayer()
                open() // persisted selection survives a new controller/Compose player.
                f.awaitValue("glsl-shaders", shader.toString())
                settings.setDesktopAnimeMode(DesktopAnimeMode.Off)
                f.awaitValue("glsl-shaders", "")
                f.assertIntegration(f.values())
                assertEquals("", f.values()["vf"])
            }
        } finally {
            settings.setDesktopAnimeSvpEnabled(previous.desktopAnimeSvpEnabled)
            settings.setDesktopAnimeMode(previous.desktopAnimeMode)
            settings.setDesktopAnimeModeAutoEnabled(previous.desktopAnimeModeAutoEnabled)
            settings.setDesktopCustomShaderPaths(previous.desktopCustomShaderPaths)
            settings.setDesktopCustomShaderSelectedPath(previous.desktopCustomShaderSelectedPath)
            settings.clearDesktopAnimeSessionState()
            previous.desktopAnimeSessionOverride?.let(settings::setDesktopAnimeSessionOverride)
        }
    }

    @Test
    fun privateRuntimeCompilesEveryPresetAndRendersCustomShaderThenRestoresBaseline() {
        if (!enabled()) return
        LinuxBaseConfigurationTest.Fixture().use { f ->
            val artifact = Path.of("build/reports/linux-shaders").toAbsolutePath()
            Files.createDirectories(artifact)
            listOf("baseline.png", "custom.png", "cleared.png", "mpv.log").forEach { Files.deleteIfExists(artifact.resolve(it)) }
            val log = artifact.resolve("mpv.log")
            PlayerSettingsRepository.setDesktopMpvConfigMode(DesktopMpvConfigMode.Replace)
            f.openController(custom = "log-file=$log\nmsg-level=all=info\nloop-file=inf")
            val base = PlayerSettingsRepository.uiState.value.copy(desktopMpvConfigMode = DesktopMpvConfigMode.Off,
                desktopAnimeModeAutoEnabled = true, desktopAnimeSessionOverride = null,
                desktopAnimeSkipUltraHdEnabled = true, desktopAnimeMode = DesktopAnimeMode.Off)
            fun apply(settings: PlayerSettingsUiState) =
                f.controller!!.applyLinuxShaderProfile(settings, true, DesktopVideoSourceSize(64, 64), confirmedSdr = true)
            apply(base)
            f.controller!!.play()
            f.waitFor { (f.values()["time-pos"]?.toDoubleOrNull() ?: 0.0) > 0.2 }
            fun sample(label: String) {
                val bean = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
                val cpu = bean.processCpuTime
                val start = System.nanoTime()
                val drops = f.values()["frame-drop-count"]?.toIntOrNull() ?: 0
                Thread.sleep(1500)
                val v = f.values()
                println("PERF $label cpuOneCorePercent=${100.0 * (bean.processCpuTime - cpu) / (System.nanoTime() - start)} " +
                    "dropped=${(v["frame-drop-count"]?.toIntOrNull() ?: 0) - drops} decoderDropped=${v["decoder-frame-drop-count"]} " +
                    "vo=${v["vo"]} api=${v["gpu-api"]} context=${v["gpu-context"]} mpv=${v["mpv-version"]}")
            }
            sample("baseline")
            for (preset in DesktopAnimeMode.entries.filter { it != DesktopAnimeMode.Off && it != DesktopAnimeMode.CustomShader }) {
                apply(base.copy(desktopAnimeMode = preset))
                val expected = DesktopAnimeShaders.shaderPaths(preset)
                f.awaitValue("glsl-shaders", expected.joinToString(":"))
                Thread.sleep(350)
                f.waitFor { f.values()["passes"]?.contains("Anime4K") == true }
                println("LOADED ${preset.name}: ${f.values()["glsl-shaders"]}; passes=${f.values()["passes"]}")
                assertFalse(Files.readString(log).contains("Failed compiling"), preset.name)
            }
            apply(base.copy(desktopAnimeMode = DesktopAnimeMode.ModeAFast))
            sample("ModeAFast")
            apply(base)
            Thread.sleep(300)
            f.screenshot(artifact.resolve("baseline.png"))
            val shader = f.directory.resolve("test shader 日本語:magenta.glsl")
            Files.writeString(shader, diagnosticShader)
            val custom = base.copy(desktopAnimeMode = DesktopAnimeMode.CustomShader,
                desktopCustomShaderPaths = shader.toString(), desktopCustomShaderSelectedPath = shader.toString())
            apply(custom)
            f.awaitValue("glsl-shaders", shader.toString())
            f.waitFor { f.values()["passes"]?.contains("NUVIO_TEST_MAGENTA") == true }
            Thread.sleep(300)
            f.screenshot(artifact.resolve("custom.png"))
            apply(base)
            f.awaitValue("glsl-shaders", "")
            Thread.sleep(300)
            f.screenshot(artifact.resolve("cleared.png"))
            fun rgb(name: String): List<Int> {
                val img = ImageIO.read(artifact.resolve(name).toFile())
                val pixel = img.getRGB(img.width / 2, img.height / 2)
                return listOf((pixel shr 16) and 255, (pixel shr 8) and 255, pixel and 255)
            }
            val baseline = rgb("baseline.png")
            val effect = rgb("custom.png")
            val cleared = rgb("cleared.png")
            println("PIXELS baseline=$baseline custom=$effect cleared=$cleared")
            assertTrue(effect[0] > 230 && effect[1] < 25 && effect[2] > 230)
            assertTrue(baseline.zip(cleared).all { (a, b) -> kotlin.math.abs(a - b) < 5 })
            assertFalse(Regex("(?i)(failed.*compil|compil.*error|failed.*shader|shader.*failed)").containsMatchIn(Files.readString(log)))
            // Existing but invalid GLSL reaches libplacebo, which diagnoses and bypasses it.
            val malformed = f.directory.resolve("malformed.glsl")
            Files.writeString(malformed, "//!HOOK MAIN\n//!BIND HOOKED\nvec4 hook() { this_is_invalid; }\n")
            apply(custom.copy(desktopCustomShaderPaths = malformed.toString(), desktopCustomShaderSelectedPath = malformed.toString()))
            Thread.sleep(500)
            val pos = f.values().getValue("time-pos").toDouble()
            f.waitFor { kotlin.math.abs(f.values().getValue("time-pos").toDouble() - pos) > 0.2 }
            assertTrue(Files.readString(log).contains("this_is_invalid"))
            println("MALFORMED compiler diagnosed this_is_invalid; playback continued")
            Files.delete(shader)
            apply(custom)
            f.awaitValue("glsl-shaders", "")
            f.assertIntegration(f.values())
            assertEquals("", f.values()["vf"])
            for (hdr in listOf(true, null)) {
                f.controller!!.applyLinuxBaseVideoProfile(base.copy(desktopColorProfile = DesktopColorProfile.Vivid), hdr)
                f.assertGrade(0, 0, 0, 0)
            }
            println("SAFEGUARDS vf=${f.values()["vf"]} target-trc=${f.values()["target-trc"]} tone-mapping=${f.values()["tone-mapping"]}")
        }
    }

    @Test
    fun shaderOptionRespectsAllCustomModesAndRendererStaysMandatory() {
        if (!enabled()) return
        LinuxBaseConfigurationTest.Fixture().use { f ->
            val shader = f.directory.resolve("user.glsl")
            Files.writeString(shader, diagnosticShader)
            for (key in listOf("glsl-shaders")) {
                for (mode in DesktopMpvConfigMode.entries) {
                    PlayerSettingsRepository.setDesktopMpvConfigMode(mode)
                    f.openController(custom = "$key=$shader\nvo=gpu\ngpu-api=opengl\ngpu-context=x11egl")
                    val settings = PlayerSettingsRepository.uiState.value.copy(desktopAnimeMode = DesktopAnimeMode.ModeAFast,
                        desktopAnimeModeAutoEnabled = true, desktopAnimeSessionOverride = null)
                    val customWins = mode == DesktopMpvConfigMode.Replace || mode == DesktopMpvConfigMode.Full
                    f.awaitValue("glsl-shaders", if (customWins) shader.toString() else "")
                    f.controller!!.applyLinuxShaderProfile(settings, true, DesktopVideoSourceSize(64, 64), confirmedSdr = true)
                    f.awaitValue("glsl-shaders", if (customWins) shader.toString() else
                        DesktopAnimeShaders.shaderPaths(DesktopAnimeMode.ModeAFast).joinToString(":"))
                    f.assertIntegration(f.values())
                    f.closePlayer()
                }
            }
            val secondShader = f.directory.resolve("second shader.glsl")
            Files.writeString(secondShader, diagnosticShader)
            for (mode in listOf(DesktopMpvConfigMode.Replace, DesktopMpvConfigMode.Full)) {
                PlayerSettingsRepository.setDesktopMpvConfigMode(mode)
                f.openController(custom = "glsl-shaders=$shader:$secondShader")
                f.awaitValue("glsl-shaders", "$shader:$secondShader")
                f.controller!!.applyLinuxShaderProfile(PlayerSettingsRepository.uiState.value, false, null)
                f.awaitValue("glsl-shaders", "$shader:$secondShader")
                f.closePlayer()
            }
        }
    }

    companion object {
        private const val diagnosticShader = "//!HOOK MAIN\n//!BIND HOOKED\n//!DESC NUVIO_TEST_MAGENTA\n" +
            "vec4 hook() { return vec4(1.0, 0.0, 1.0, 1.0); }\n"
    }
}
