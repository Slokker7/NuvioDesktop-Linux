package com.nuvio.app.features.player.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import co.touchlab.kermit.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** A connection-owned session lease. Blocking backend calls run only on the owner's IO worker. */
internal fun interface LinuxInhibitionBackend {
    fun acquire(onLost: () -> Unit): AutoCloseable?
}

/** Aggregates independent composition clients into one lease; no player/native-window dependency. */
internal class LinuxPlaybackInhibitor(private val backend: LinuxInhibitionBackend) : AutoCloseable {
    private val lock = Any()
    private val clients = mutableSetOf<Client>()
    private var closed = false
    private var revision = 0L
    private var attemptToken = 0L
    private var lostToken = -1L
    private var lossRetryAvailable = true
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val finished = CountDownLatch(1)
    private val job = SupervisorJob()
    private val log = Logger.withTag("LinuxPlaybackInhibitor")

    init {
        CoroutineScope(job + Dispatchers.IO).launch {
            var lease: AutoCloseable? = null
            var leaseToken = -1L
            var attemptedRevision = -1L
            fun release() {
                val old = lease
                lease = null
                if (old != null) runCatching { old.close() }.onFailure { log.w { "Release failed: ${it.javaClass.simpleName}" } }
            }
            try {
                for (ignored in changes) {
                    val state = synchronized(lock) { Triple(!closed && clients.isNotEmpty(), revision, lostToken) }
                    if (!state.first || state.third == leaseToken) release()
                    if (synchronized(lock) { closed }) break
                    if (!state.first || lease != null || attemptedRevision == state.second) continue
                    attemptedRevision = state.second
                    val token = synchronized(lock) { ++attemptToken }
                    val acquired = try {
                        backend.acquire { lost(token) }
                    } catch (error: Exception) {
                        log.w { "Acquisition failed: ${error.javaClass.simpleName}; playback continues" }
                        null
                    } catch (error: LinkageError) {
                        log.w { "Native inhibition unavailable; playback continues" }
                        null
                    }
                    lease = acquired
                    leaseToken = token
                    // A slow acquisition can finish after pause/disposal. Never publish it as owned.
                    val retain = synchronized(lock) { !closed && clients.isNotEmpty() && lostToken != token }
                    if (!retain) release()
                }
            } finally {
                release()
                finished.countDown()
                job.complete()
            }
        }
    }

    fun client(): Client = Client()

    inner class Client internal constructor() : AutoCloseable {
        private var disposed = false
        fun setAwake(awake: Boolean) = synchronized(lock) {
            if (closed || disposed) return@synchronized
            val wasWanted = clients.isNotEmpty()
            if (awake) clients.add(this) else clients.remove(this)
            if (wasWanted != clients.isNotEmpty()) {
                revision++
                lossRetryAvailable = true
                changes.trySend(Unit)
            }
        }
        override fun close() = synchronized(lock) {
            setAwake(false)
            disposed = true
        }
    }

    private fun lost(token: Long) = synchronized(lock) {
        if (closed || token != attemptToken || lostToken == token) return@synchronized
        lostToken = token
        // One event-driven reconnect per demand transition. No retry timer/spin if services stay down.
        if (lossRetryAvailable) {
            lossRetryAvailable = false
            revision++
        }
        changes.trySend(Unit)
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            clients.clear()
            changes.trySend(Unit)
            changes.close()
        }
    }

    fun awaitClosed(timeoutMs: Long): Boolean = finished.await(timeoutMs, TimeUnit.MILLISECONDS)
}

private object LinuxSessionInhibition {
    val owner = LinuxPlaybackInhibitor(LinuxInhibitionBackend { onLost ->
        NativePlayerBridge.ensureNativeLibraryLoaded()
        val handle = LinuxInhibitionNative.acquire(Runnable(onLost))
        if (handle == 0L) null else {
            val pointer = AtomicLong(handle)
            AutoCloseable { pointer.getAndSet(0L).takeIf { it != 0L }?.let(LinuxInhibitionNative::release) }
        }
    }).also { owner ->
        Runtime.getRuntime().addShutdownHook(Thread({
            owner.close()
            owner.awaitClosed(10_000)
        }, "nuvio-inhibition-shutdown"))
    }
}

internal object LinuxInhibitionNative {
    external fun acquire(onLost: Runnable): Long
    external fun release(handle: Long)
}

@Composable
internal fun LinuxPlaybackInhibition(keepScreenAwake: Boolean) {
    val client = remember { LinuxSessionInhibition.owner.client() }
    DisposableEffect(client) { onDispose { client.close() } }
    SideEffect { client.setAwake(keepScreenAwake) }
}
