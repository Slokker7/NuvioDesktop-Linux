package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal data class LinuxMprisState(
    val token: Long = 0,
    val trackId: String = "",
    val title: String = "",
    val album: String = "",
    val artwork: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val status: String = "Stopped",
    val durationUs: Long = 0,
    val positionUs: Long = 0,
    val canSeek: Boolean = false,
    val canNext: Boolean = false,
    val canPrevious: Boolean = false,
    val rate: Double = 1.0,
    val volume: Double = 1.0,
    val seekSerial: Long = 0,
) {
    val active: Boolean get() = trackId.isNotEmpty()
}

/** Artwork is public metadata, never the playback URL. Reject userinfo and signed/query URLs. */
internal fun publicMprisArtwork(value: String): String = runCatching {
    val uri = URI(value)
    value.takeIf { uri.scheme in listOf("http", "https") && !uri.host.isNullOrEmpty() &&
        uri.userInfo == null && uri.rawQuery == null && uri.fragment == null }.orEmpty()
}.getOrDefault("")

internal fun interface LinuxMprisCommandSink {
    fun command(token: Long, method: String, value: Double, trackId: String)
}

internal interface LinuxMprisLease : AutoCloseable {
    fun publish(state: LinuxMprisState)
}
internal fun interface LinuxMprisBackend {
    fun acquire(sink: LinuxMprisCommandSink, onLost: () -> Unit): LinuxMprisLease?
}

/** One process owner. All native acquisition, publication and teardown run on one IO worker. */
internal class LinuxMprisSession(
    private val backend: LinuxMprisBackend,
    private val dispatch: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
) : AutoCloseable {
    private val lock = Any()
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val finished = CountDownLatch(1)
    private val job = SupervisorJob()
    private val log = Logger.withTag("LinuxMprisSession")
    private var closed = false
    @Volatile private var wanted = false
    private var revision = 0L
    private var retryAvailable = true
    private var attempt = 0L
    private var lostAttempt = -1L
    private var sequence = 0L
    private var current: Client? = null
    private var state = LinuxMprisState()

    init {
        CoroutineScope(job + Dispatchers.IO).launch {
            var lease: LinuxMprisLease? = null
            var leaseAttempt = -1L
            var attemptedRevision = -1L
            fun release() {
                val old = lease
                lease = null
                runCatching { old?.close() }.onFailure { log.w { "MPRIS release failed" } }
            }
            try {
                for (ignored in changes) {
                    val snapshot = synchronized(lock) { Triple(closed, revision, lostAttempt) }
                    if (snapshot.first || snapshot.third == leaseAttempt) release()
                    if (snapshot.first) break
                    if (lease == null && wanted && attemptedRevision != snapshot.second) {
                        attemptedRevision = snapshot.second
                        val token = synchronized(lock) { ++attempt }
                        lease = runCatching {
                            backend.acquire(LinuxMprisCommandSink { generation, method, value, track ->
                                // The service's connection epoch is checked as well as the player epoch.
                                dispatch {
                                    synchronized(lock) {
                                        if (!closed && token == attempt && lostAttempt != token &&
                                            generation == state.token && state.active) {
                                            current?.accept(method, value, track)
                                        }
                                    }
                                }
                            }) { lost(token) }
                        }.onFailure { log.w { "MPRIS unavailable; playback continues (${it.javaClass.simpleName})" } }.getOrNull()
                        leaseAttempt = token
                    }
                    if (synchronized(lock) { closed || lostAttempt == leaseAttempt }) release()
                    else lease?.let { active ->
                        runCatching { active.publish(synchronized(lock) { state }) }
                            .onFailure { lost(leaseAttempt) }
                    }
                }
            } finally { release(); finished.countDown(); job.complete() }
        }
    }

    fun client(onCommand: (String, Double) -> Unit): Client = synchronized(lock) {
        Client(onCommand).also {
            current?.disposed = true
            current = it
            state = LinuxMprisState(token = ++sequence)
            wanted = true
            revision++
            retryAvailable = true
            changes.trySend(Unit)
        }
    }

    inner class Client internal constructor(private val onCommand: (String, Double) -> Unit) : AutoCloseable {
        internal var disposed = false
        fun publish(value: LinuxMprisState) = synchronized(lock) {
            if (closed || disposed || current !== this || value.token != state.token) return@synchronized
            state = value.copy(artwork = publicMprisArtwork(value.artwork))
            changes.trySend(Unit)
        }
        /** Invalidate queued callbacks immediately, even while a slow source attach is running. */
        fun newSource(): Long = synchronized(lock) {
            val token = ++sequence
            if (!closed && !disposed && current === this) {
                state = LinuxMprisState(token = token)
                changes.trySend(Unit)
            }
            token
        }
        internal fun accept(method: String, value: Double, track: String) {
            if (disposed || current !== this) return
            when (method) {
                "Play", "Pause", "PlayPause" -> onCommand(method, value)
                "Next" -> if (state.canNext) onCommand(method, value)
                "Previous" -> if (state.canPrevious) onCommand(method, value)
                "Seek" -> if (state.canSeek) onCommand(method, value)
                "SetPosition" -> if (state.canSeek && track == state.trackId && value >= 0 &&
                    value <= state.durationUs) onCommand(method, value)
                "Volume", "Rate" -> if (value.isFinite()) onCommand(method, value)
            }
        }
        override fun close() = synchronized(lock) {
            if (disposed) return@synchronized
            disposed = true
            if (current === this) {
                current = null
                state = LinuxMprisState(token = ++sequence)
                changes.trySend(Unit)
            }
        }
    }

    private fun lost(token: Long) = synchronized(lock) {
        if (closed || token != attempt || lostAttempt == token) return@synchronized
        lostAttempt = token
        if (retryAvailable) { retryAvailable = false; revision++ }
        changes.trySend(Unit)
    }
    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            current?.disposed = true
            current = null
            state = LinuxMprisState(token = ++sequence)
            changes.trySend(Unit)
            changes.close()
        }
    }
    fun awaitClosed(timeoutMs: Long): Boolean = finished.await(timeoutMs, TimeUnit.MILLISECONDS)
}

internal object LinuxMprisNative {
    external fun acquire(sink: LinuxMprisCommandSink, onLost: Runnable, busName: String, busAddress: String?): Long
    external fun seekable(playerHandle: Long): Boolean
    external fun publish(handle: Long, token: Long, track: String, title: String, album: String,
        artwork: String, season: Int, episode: Int, status: String, duration: Long, position: Long,
        seek: Boolean, next: Boolean, previous: Boolean, rate: Double, volume: Double, seekSerial: Long)
    external fun release(handle: Long)
}

internal fun nativeMprisBackend(
    busName: String = "org.mpris.MediaPlayer2.Nuvio",
    busAddress: String? = null,
): LinuxMprisBackend =
    LinuxMprisBackend { sink, lost ->
        NativePlayerBridge.ensureNativeLibraryLoaded()
        val handle = LinuxMprisNative.acquire(sink, Runnable(lost), busName, busAddress)
        if (handle == 0L) null else object : LinuxMprisLease {
            private val pointer = AtomicLong(handle)
            override fun publish(state: LinuxMprisState) {
                val p = pointer.get()
                if (p != 0L) with(state) { LinuxMprisNative.publish(p, token, trackId, title, album,
                    artwork, season, episode, status, durationUs, positionUs, canSeek, canNext,
                    canPrevious, rate, volume, seekSerial) }
            }
            override fun close() { pointer.getAndSet(0L).takeIf { it != 0L }?.let(LinuxMprisNative::release) }
        }
    }

internal object LinuxMediaSession {
    val owner = LinuxMprisSession(nativeMprisBackend()).also { owner ->
        Runtime.getRuntime().addShutdownHook(Thread({ owner.close(); owner.awaitClosed(10_000) }, "nuvio-mpris-shutdown"))
    }
}
