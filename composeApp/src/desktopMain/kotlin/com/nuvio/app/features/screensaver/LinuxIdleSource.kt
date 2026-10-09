package com.nuvio.app.features.screensaver

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal fun interface LinuxIdleSource : AutoCloseable {
    fun idleMs(): Long?
    override fun close() = Unit
}

/** All native calls, including destruction, belong to one worker, never the AWT thread. */
internal class LinuxSessionIdleSource(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val open: () -> LinuxIdleSource = {
        NativePlayerBridge.ensureNativeLibraryLoaded()
        val handle = LinuxIdleNative.open()
        check(handle != 0L)
        object : LinuxIdleSource {
            override fun idleMs(): Long? = LinuxIdleNative.query(handle).takeIf { it >= 0 }
            override fun close() = LinuxIdleNative.close(handle)
        }
    },
) : LinuxIdleSource {
    private data class Sample(val idle: Long, val at: Long)
    @Volatile private var sample: Sample? = null
    @Volatile private var closed = false
    private var backend: LinuxIdleSource? = null
    private var retryAt = 0L
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "nuvio-idle-probe").apply { isDaemon = true }
    }
    private val poll = worker.scheduleWithFixedDelay({
        if (!closed) {
            try {
                if (backend == null && clock() >= retryAt) backend = open()
                val idle = backend?.idleMs()
                sample = idle?.let { Sample(it, clock()) }
                if (idle == null) release()
            } catch (error: Exception) {
                release()
            } catch (error: LinkageError) {
                release()
            }
        }
    }, 0, 1, TimeUnit.SECONDS)

    private fun release() {
        sample = null
        runCatching { backend?.close() }
        backend = null
        if (!closed && clock() >= retryAt) {
            Logger.withTag("LinuxScreensaver").i { "Session idle unavailable; using app input (retry in 60s)" }
            retryAt = clock() + 60_000
        }
    }

    override fun idleMs(): Long? {
        if (closed) return null
        val value = sample ?: return null
        val age = clock() - value.at
        // A blocked/disconnected service must not leave an ever-growing stale idle estimate.
        return if (age in 0..3_000) value.idle + age else null
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        sample = null
        poll.cancel(false)
        worker.execute { release() }
        worker.shutdown()
    }

    internal fun awaitClosed(timeoutMs: Long): Boolean = worker.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
}

internal object LinuxIdleNative {
    external fun open(): Long
    external fun query(handle: Long): Long
    external fun close(handle: Long)
}
