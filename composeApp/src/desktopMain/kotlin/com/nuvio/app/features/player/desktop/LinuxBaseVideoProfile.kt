package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.DesktopColorGrade
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.player.desktopColorGrade
import com.nuvio.app.features.player.presetGrade

/** The ordinary desktop picture settings, without HDR output, RTX, SVP or shader ownership. */
internal fun NativePlayerController.applyLinuxBaseVideoProfile(settings: PlayerSettingsUiState, isHdr: Boolean?) {
    // Until Linux has its own validated HDR-output path, only confirmed SDR receives SDR grading.
    val grade = if (isHdr == false) settings.desktopColorProfile.presetGrade(settings.desktopColorGrade())
        else DesktopColorGrade(0, 0, 0, 0)
    val customNames = desktopCustomMpvOptionNames(settings.desktopCustomMpvOptions)
    withVideoProfile {
        for ((key, value) in listOf("contrast" to grade.contrast, "brightness" to grade.brightness,
            "saturation" to grade.saturation, "gamma" to grade.gamma)) {
            if (shouldApplyNuvioRuntimeMpvProperty(settings.desktopMpvConfigMode, customNames, key)) {
                setMpvProperty(key, value.toString())
            }
        }
        applyDesktopBufferPreset(settings.desktopBufferPreset)
    }
}
