package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerControlsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxHeroPlaybackEligibilityTest {
    @Test fun homeRequiresPlaybackForegroundRouteAndActiveHomeTab() {
        for (playing in listOf(false, true)) for (resumed in listOf(false, true)) for (home in listOf(false, true)) {
            assertEquals(playing && resumed && home, linuxHeroPlaybackEligible(playing, resumed, home))
        }
    }

    @Test fun pausedRetainedDetailsAndOutgoingRoutesCannotOwnAPlayer() {
        assertFalse(linuxHeroPlaybackEligible(playWhenReady = false, routeResumed = true))
        assertFalse(linuxHeroPlaybackEligible(playWhenReady = true, routeResumed = false))
        assertTrue(linuxHeroPlaybackEligible(playWhenReady = true, routeResumed = true))
    }

    @Test fun mutedStartupIsSilentEvenWithRememberedPositiveVolume() {
        assertEquals(listOf("mute=yes", "volume=0"), linuxHeroStartupAudioOptions(
            PlayerControlsState(heroTrailerMode = true, heroTrailerMuted = true, heroTrailerVolume = 60),
        ))
    }

    @Test fun unmutedStartupUsesSharedVolumeWithinItsExistingBounds() {
        for ((input, expected) in listOf(-1 to 0, 0 to 0, 60 to 60, 100 to 100, 150 to 100)) {
            assertEquals(listOf("mute=no", "volume=$expected"), linuxHeroStartupAudioOptions(
                PlayerControlsState(heroTrailerMode = true, heroTrailerMuted = false, heroTrailerVolume = input),
            ))
        }
    }
}
