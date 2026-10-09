package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.DesktopAnimeMode
import com.nuvio.app.features.player.DesktopAnimeShaderSkipReason
import com.nuvio.app.features.player.DesktopVideoSourceSize
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.player.desktopActiveAnimeMode
import com.nuvio.app.features.player.planDesktopAnimeShaders
import java.io.File

// mpv's CLI-only glsl-shader/list-action aliases are not libmpv options. Use the canonical
// glsl-shaders property, also accepted by the existing custom mpv options path.
internal fun linuxShaderPathList(paths: List<String>): String =
    paths.joinToString(":") { it.replace(":", "\\:") }

internal data class LinuxShaderProfile(val chain: String, val label: String?)

/** Shader selection only: no grading, scaling, vf, hwdec, HDR, RTX or SVP mutations. */
internal fun linuxShaderProfile(
    settings: PlayerSettingsUiState,
    isAnime: Boolean,
    sourceSize: DesktopVideoSourceSize?,
    confirmedSdr: Boolean = false,
): LinuxShaderProfile? {
    if (!shouldApplyNuvioRuntimeMpvProperty(settings.desktopMpvConfigMode,
            desktopCustomMpvOptionNames(settings.desktopCustomMpvOptions),
            "glsl-shaders")) return null
    // Ownership is checked first: explicit Replace/Full options belong to the user.
    // Session forces and the UHD preference only affect selection AFTER media eligibility.
    if (!confirmedSdr || sourceSize == null || sourceSize.width <= 0 || sourceSize.height <= 0)
        return LinuxShaderProfile("", null)
    val active = desktopActiveAnimeMode(settings.desktopAnimeMode, settings.desktopAnimeModeAutoEnabled,
        settings.desktopAnimeSessionOverride, isAnime)
    val custom = if (active == DesktopAnimeMode.CustomShader) {
        DesktopCustomShaders.shaderChain(settings.desktopCustomShaderPaths,
            settings.desktopAnimeSessionOverride?.customShaderPath ?: settings.desktopCustomShaderSelectedPath)
            // Revalidate even a cached catalog entry: deletion/permission changes cannot leave a stale path.
            .takeIf { it.isNotEmpty() && File(it).isFile && File(it).canRead() }.orEmpty()
    } else ""
    val plan = planDesktopAnimeShaders(
        requestedPreset = active.takeUnless { it == DesktopAnimeMode.Off || it == DesktopAnimeMode.CustomShader },
        requestedCustomShaderChain = custom,
        isSessionForced = settings.desktopAnimeSessionOverride != null,
        skipUltraHdSources = settings.desktopAnimeSkipUltraHdEnabled,
        sourceSize = sourceSize,
    )
    val paths = plan.preset?.let(DesktopAnimeShaders::shaderPaths)
        ?: listOfNotNull(plan.customShaderChain.takeIf(String::isNotEmpty))
    val label = when {
        plan.skipReason == DesktopAnimeShaderSkipReason.UltraHdSource ->
            if (active == DesktopAnimeMode.CustomShader) "Shader off — 4K source" else "Anime4K off — 4K source"
        paths.isEmpty() -> null
        plan.preset != null -> "Anime4K ${plan.preset.label}"
        else -> File(custom).nameWithoutExtension
    }
    return LinuxShaderProfile(linuxShaderPathList(paths), label)
}

internal fun NativePlayerController.applyLinuxShaderProfile(
    settings: PlayerSettingsUiState,
    isAnime: Boolean,
    sourceSize: DesktopVideoSourceSize?,
    confirmedSdr: Boolean = false,
): String? {
    val profile = linuxShaderProfile(settings, isAnime, sourceSize, confirmedSdr) ?: return null
    withVideoProfile { setMpvProperty("glsl-shaders", profile.chain) }
    return profile.label
}
