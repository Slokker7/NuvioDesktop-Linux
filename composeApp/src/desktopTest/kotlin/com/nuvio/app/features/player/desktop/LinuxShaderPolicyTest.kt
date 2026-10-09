package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.*
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class LinuxShaderPolicyTest {
    private val size = DesktopVideoSourceSize(1920, 1080)
    private val settings = PlayerSettingsUiState(desktopAnimeMode = DesktopAnimeMode.ModeAFast,
        desktopAnimeModeAutoEnabled = true, desktopMpvConfigMode = DesktopMpvConfigMode.Off)

    private fun sdrProfile(settings: PlayerSettingsUiState, anime: Boolean, size: DesktopVideoSourceSize?) =
        linuxShaderProfile(settings, anime, size, confirmedSdr = true)

    private fun media(gamma: String = "bt.1886", primaries: String = "bt.709", matrix: String = "bt.709",
                      size: DesktopVideoSourceSize? = this.size) = LinuxShaderMedia.fromEvent(
        "${LinuxShaderMedia.EVENT_PREFIX}$gamma/$primaries/$matrix",
        size?.let { (it.width.toLong() * 65536 + it.height).toDouble() } ?: 0.0)

    @Test fun unclassifiedMediaNeverAppliesEvenWithKnownDimensions() {
        assertEquals("", linuxShaderProfile(settings, true, size)!!.chain)
        assertEquals("", linuxShaderProfile(settings.copy(desktopAnimeSessionOverride =
            DesktopAnimeSessionOverride(DesktopAnimeMode.Hq)), true, size)!!.chain)
        for (unknown in listOf(media(""), media("auto"), media("new-transfer"), media(primaries = ""), media(matrix = "")))
            assertFalse(unknown.confirmedSdr)
    }

    @Test fun hdr10Hdr10PlusDolbyVisionAndHlgNeverQualify() {
        for ((label, metadata) in mapOf(
            "HDR10" to media("pq", "bt.2020", "bt.2020-ncl"),
            "HDR10+" to media("pq", "bt.2020", "bt.2020-ncl"),
            "Dolby Vision" to media("pq", "bt.2020", "dolbyvision"),
            "HLG" to media("hlg", "bt.2020", "bt.2020-ncl"),
            "PQ alias" to media("st2084"), "HLG alias" to media("arib-std-b67"),
            "DV matrix" to media(matrix = "dolbyvision"),
            "wide gamut" to media(primaries = "bt.2020"))) {
            assertFalse(metadata.confirmedSdr, label)
            for (forced in listOf(null, DesktopAnimeSessionOverride(DesktopAnimeMode.Hq))) {
                assertEquals("", linuxShaderProfile(settings.copy(desktopAnimeSessionOverride = forced,
                    desktopAnimeSkipUltraHdEnabled = false), true, metadata.size, metadata.confirmedSdr)!!.chain, label)
            }
        }
    }

    @Test fun sdrEligibilityDimensionsAndAllTransitions() {
        val sdr = media()
        assertTrue(sdr.confirmedSdr)
        val hdr = media("pq", "bt.2020", "dolbyvision")
        val unknown = media("")
        for (sequence in listOf(listOf(unknown, sdr, hdr, sdr, sdr), listOf(unknown, hdr, hdr, sdr, hdr))) {
            for (m in sequence) assertEquals(m.confirmedSdr,
                linuxShaderProfile(settings, true, m.size, m.confirmedSdr)!!.chain.isNotEmpty())
        }
        assertEquals("", linuxShaderProfile(settings, true, null, true)!!.chain)
        assertEquals("", linuxShaderProfile(settings, true, DesktopVideoSourceSize(0, 1080), true)!!.chain)
        assertEquals("", linuxShaderProfile(settings, true, DesktopVideoSourceSize(1920, -1), true)!!.chain)
        assertTrue(linuxShaderProfile(settings, true, size, true)!!.chain.isNotEmpty())
        assertEquals("", linuxShaderProfile(settings.copy(desktopAnimeSessionOverride =
            DesktopAnimeSessionOverride(DesktopAnimeMode.Off)), true, size, true)!!.chain)
    }

    @Test fun unsafeMediaStillPreservesExplicitUserOwnership() {
        for (mode in DesktopMpvConfigMode.entries) {
            val result = linuxShaderProfile(settings.copy(desktopMpvConfigMode = mode,
                desktopCustomMpvOptions = "glsl-shaders=user.glsl"), true, size, false)
            if (mode == DesktopMpvConfigMode.Full || mode == DesktopMpvConfigMode.Replace) assertNull(result)
            else assertEquals("", result!!.chain)
        }
    }

    @Test fun presetChainIsOrderedAndOffHasNoResources() {
        assertEquals(listOf("Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_M.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl", "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl", "Anime4K_Upscale_CNN_x2_S.glsl"),
            DesktopAnimeShaders.shaderPaths(DesktopAnimeMode.ModeAFast).map { java.io.File(it).name })
        assertTrue(DesktopAnimeShaders.shaderPaths(DesktopAnimeMode.Off).isEmpty())
        assertEquals(listOf(7, 10, 10, 6, 6, 6, 6, 5, 5), DesktopAnimeMode.entries
            .filter { it != DesktopAnimeMode.Off && it != DesktopAnimeMode.CustomShader }
            .map { DesktopAnimeShaders.shaderPaths(it).size })
    }

    @Test fun autoRequiresBothEligibilityAndEnabledMode() {
        assertTrue(sdrProfile(settings, true, size)!!.chain.isNotEmpty())
        assertEquals("", sdrProfile(settings, false, size)!!.chain)
        assertEquals("", sdrProfile(settings.copy(desktopAnimeModeAutoEnabled = false), true, size)!!.chain)
        assertEquals("", sdrProfile(settings.copy(desktopAnimeMode = DesktopAnimeMode.Off), true, size)!!.chain)
    }

    @Test fun unknownDimensionsCannotBeBypassedBySessionForceOrUhdPreference() {
        assertEquals("", sdrProfile(settings.copy(desktopAnimeSkipUltraHdEnabled = false), true, null)!!.chain)
        assertEquals("", sdrProfile(settings.copy(desktopAnimeSessionOverride =
            DesktopAnimeSessionOverride(DesktopAnimeMode.ModeAFast)), false, null)!!.chain)
    }

    @Test fun westernAnimationUsesExistingClassification() {
        val western = classifyAnimeContent(listOf("Animation"), "en", listOf("US"))
        assertEquals("", sdrProfile(settings, western.isAnime(false), size)!!.chain)
        assertTrue(sdrProfile(settings, western.isAnime(true), size)!!.chain.isNotEmpty())
    }

    @Test fun customCatalogSelectsOneFileAndRevalidatesPermissionsAndDeletion() {
        if (DesktopHostOs.current != DesktopHostOs.LINUX) return
        val dir = Files.createTempDirectory("nuvio-shader-policy-")
        try {
            val first = dir.resolve("a 日本語.glsl")
            val second = dir.resolve("b test.hook")
            Files.writeString(first, "malformed GLSL is diagnosed by libplacebo, not parsed by Kotlin")
            Files.writeString(second, "// shader")
            val custom = settings.copy(desktopAnimeMode = DesktopAnimeMode.CustomShader,
                desktopCustomShaderPaths = dir.toString(), desktopCustomShaderSelectedPath = first.toString())
            assertEquals("", linuxShaderProfile(custom, true, size, false)!!.chain)
            assertEquals("", linuxShaderProfile(custom.copy(desktopAnimeSessionOverride =
                DesktopAnimeSessionOverride(DesktopAnimeMode.CustomShader, first.toString())), false, size, false)!!.chain)
            assertEquals(first.toString(), sdrProfile(custom, true, size)!!.chain)
            assertEquals(second.toString(), sdrProfile(custom.copy(desktopCustomShaderSelectedPath = second.toString()), true, size)!!.chain)
            Files.setPosixFilePermissions(first, PosixFilePermissions.fromString("---------"))
            assertEquals("", sdrProfile(custom, true, size)!!.chain)
            Files.delete(first)
            assertEquals("", sdrProfile(custom, true, size)!!.chain)
            assertEquals("", sdrProfile(custom.copy(desktopCustomShaderPaths = ""), true, size)!!.chain)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun colonSpacesUnicodeAndBackslashArePathListSafe() {
        assertEquals("/tmp/a\\:b 日本語.glsl:/tmp/c\\d.glsl",
            linuxShaderPathList(listOf("/tmp/a:b 日本語.glsl", "/tmp/c\\d.glsl")))
    }

    @Test fun canonicalShaderOptionPreservesReplaceAndFullOwnership() {
        for (mode in DesktopMpvConfigMode.entries) {
            val result = sdrProfile(settings.copy(desktopMpvConfigMode = mode,
                desktopCustomMpvOptions = "glsl-shaders=user.glsl"), true, size)
            if (mode == DesktopMpvConfigMode.Replace || mode == DesktopMpvConfigMode.Full) assertNull(result)
            else assertTrue(result!!.chain.isNotEmpty())
        }
        assertNotNull(sdrProfile(settings.copy(desktopMpvConfigMode = DesktopMpvConfigMode.Replace,
            desktopCustomMpvOptions = "glsl-shader-opts=strength=1"), true, size))
    }

    @Test fun sourceAndSettingsChangesDoNotRetainOldChain() {
        assertTrue(sdrProfile(settings, true, size)!!.chain.isNotEmpty())
        assertEquals("", sdrProfile(settings, true, null)!!.chain)
        assertEquals("", sdrProfile(settings, false, size)!!.chain)
        assertEquals("", sdrProfile(settings.copy(desktopAnimeMode = DesktopAnimeMode.Off), true, size)!!.chain)
    }
}
