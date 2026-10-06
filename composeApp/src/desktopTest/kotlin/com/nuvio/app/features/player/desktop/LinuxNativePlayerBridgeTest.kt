package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxNativePlayerBridgeTest {
    @Test
    fun libraryLoadsButPlaybackRemainsUnsupported() {
        if (DesktopHostOs.current != DesktopHostOs.LINUX ||
            System.getProperty("nuvio.linux.nativeSmokeTest") != "true"
        ) return

        // System.load invokes JNI_OnLoad, which allocates and destroys a real libmpv handle.
        NativePlayerBridge.ensureNativeLibraryLoaded()
        assertTrue(NativePlayerBridge.runtimeDllDir()?.resolve("libplayer_bridge.so")?.isFile == true)
        NativePlayerBridge.dispose(0L)
        assertFailsWith<UnsupportedOperationException> {
            NativePlayerBridge.create(
                hostViewPtr = 0L,
                sourceUrl = "",
                sourceAudioUrl = null,
                headerLines = emptyArray(),
                playWhenReady = false,
                initialPositionMs = 0L,
                initialProgressFraction = 0.0,
                controlsPageUrl = "",
                nvidiaRtxSuperResolutionEnabled = false,
                nvidiaRtxHdrEnabled = false,
                isAnimeContent = false,
                animeSvpFilter = null,
                extraMpvOptions = emptyArray(),
                eventSink = NativePlayerEventSink { _, _ -> },
            )
        }
    }
}
