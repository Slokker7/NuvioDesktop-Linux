package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerControlsState

internal fun linuxMprisTitle(controls: PlayerControlsState): String {
    val episode = if (controls.mediaSessionEpisode > 0) {
        val code = "S${controls.mediaSessionSeason.toString().padStart(2, '0')}" +
            "E${controls.mediaSessionEpisode.toString().padStart(2, '0')}"
        listOf(code, controls.pauseOverlayEpisodeTitle).filter { it.isNotBlank() }.joinToString(" — ")
    } else controls.episodeText // Already contains the episode title when available.
    return listOf(controls.title, episode).filter { it.isNotBlank() }.joinToString(" — ")
}

/** Adapter for the main player; trailers never acquire a client. Called on the existing UI path. */
internal class LinuxMprisPlayer(
    private val controller: NativePlayerController,
    owner: LinuxMprisSession = LinuxMediaSession.owner,
    private val usable: () -> Boolean,
) : AutoCloseable {
    private val client = owner.client { method, value -> if (usable()) command(method, value) }
    private var token = client.newSource()
    private var state = LinuxMprisState()
    private var seekSerial = 0L
    private var retiringHandle = 0L

    fun sourceChanging(outgoingHandle: Long) {
        retiringHandle = outgoingHandle
        token = client.newSource()
        state = LinuxMprisState(token = token)
    }

    fun update(controls: PlayerControlsState, handle: Long) {
        if (handle == 0L || handle == retiringHandle || !usable()) return
        val duration = NativePlayerBridge.durationMs(handle).coerceIn(0, Long.MAX_VALUE / 1000) * 1000
        val position = NativePlayerBridge.positionMs(handle).coerceIn(0, Long.MAX_VALUE / 1000) * 1000
        val ended = NativePlayerBridge.isEnded(handle)
        val paused = NativePlayerBridge.isPaused(handle)
        state = LinuxMprisState(
            token = token,
            trackId = "/org/mpris/MediaPlayer2/track/t$token",
            title = linuxMprisTitle(controls),
            album = controls.title.takeIf { controls.mediaSessionEpisode > 0 || controls.episodeText.isNotBlank() }.orEmpty(),
            artwork = controls.mediaSessionArtwork,
            season = controls.mediaSessionSeason,
            episode = controls.mediaSessionEpisode,
            // Buffering is still intended Playing, like Windows SMTC; mpv pause remains authoritative.
            status = if (ended) "Stopped" else if (paused) "Paused" else "Playing",
            durationUs = duration,
            positionUs = position,
            canSeek = duration > 0 && LinuxMprisNative.seekable(handle),
            canNext = controls.mediaSessionCanGoNext,
            canPrevious = controls.mediaSessionCanGoPrevious,
            rate = NativePlayerBridge.speed(handle).toDouble(),
            volume = controller.getVolume()?.let { if (it.isMuted) 0.0 else it.fraction.toDouble() } ?: 1.0,
            seekSerial = seekSerial,
        )
        client.publish(state)
    }

    private fun command(method: String, value: Double) {
        when (method) {
            "Play" -> {
                // Upstream discrete Play does not restart EOF; the toggle action does.
                if (controller.snapshot().isEnded) controller.dispatchKeyboardShortcut("keyboardToggle")
                else controller.dispatchKeyboardShortcut("mediaPlay")
            }
            "Pause" -> controller.dispatchKeyboardShortcut("mediaPause")
            "PlayPause" -> controller.dispatchKeyboardShortcut("keyboardToggle")
            "Next" -> controller.dispatchKeyboardShortcut("mediaNext")
            "Previous" -> controller.dispatchKeyboardShortcut("mediaPrevious")
            "Seek", "SetPosition" -> {
                // Resolve relative seek against live mpv position on the UI thread, not the bus snapshot.
                val target = if (method == "Seek") controller.snapshot().positionMs * 1000.0 + value else value
                if (target > state.durationUs) {
                    if (method == "Seek" && state.canNext) controller.dispatchKeyboardShortcut("mediaNext")
                } else if (method == "Seek" || target >= 0) {
                    controller.dispatchKeyboardShortcut("scrubFinish", target.coerceAtLeast(0.0) / 1000.0)
                    seekSerial++
                }
            }
            "Volume" -> controller.setVolume(value.toFloat().coerceIn(0f, controller.maxVolumeFraction))
            "Rate" -> controller.dispatchKeyboardShortcut("setPlaybackSpeed", value)
        }
    }
    override fun close() = client.close()
}
