package com.nuvio.app.features.player.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.app.features.home.components.HomeHeroTrailerGate
import com.nuvio.app.features.player.PlayerControlsState

internal fun linuxHeroPlaybackEligible(
    playWhenReady: Boolean,
    routeResumed: Boolean,
    homeActive: Boolean = true,
): Boolean = playWhenReady && routeResumed && homeActive

/** The shared surfaces/timers stay intact; only Linux native ownership is gated. */
@Composable
internal fun linuxHeroPlayWhenReady(playWhenReady: Boolean, home: Boolean = false): Boolean {
    if (DesktopHostOs.current != DesktopHostOs.LINUX) return playWhenReady
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val homeActive = if (home) {
        val active by HomeHeroTrailerGate.homeActive.collectAsState()
        active
    } else true
    return linuxHeroPlaybackEligible(playWhenReady, lifecycle == Lifecycle.State.RESUMED, homeActive)
}

// Native create loads media before queued properties are replayed. Set the shared
// hero audio state before mpv_initialize/loadfile, including retained-surface resumes.
internal fun linuxHeroStartupAudioOptions(state: PlayerControlsState): List<String> = listOf(
    "mute=${if (state.heroTrailerMuted) "yes" else "no"}",
    "volume=${if (state.heroTrailerMuted) 0 else state.heroTrailerVolume.coerceIn(0, 100)}",
)
