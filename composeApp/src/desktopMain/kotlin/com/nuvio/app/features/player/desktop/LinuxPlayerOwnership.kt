package com.nuvio.app.features.player.desktop

/**
 * Linux's main bridge reserves process-wide X11 state for one player lifetime.
 * Requests only change small state; claim/forget run under the existing native
 * lifecycle lock. No ownership wait, UI callback or native teardown holds stateLock.
 * There is deliberately no queue: an evicted hero needs fresh foreground eligibility.
 */
internal class LinuxPlayerOwnership(private val lifecycleLock: Any) {
    enum class Priority { HERO, PLAYER }
    class Request internal constructor(internal val owner: Any, internal val priority: Priority)
    private class Active(val request: Request, val quiesce: () -> Unit, val release: () -> Unit) {
        var prepared = false // Guarded by stateLock.
    }
    private val stateLock = Any()
    private var requested: Request? = null
    private var active: Active? = null

    fun request(owner: Any, priority: Priority): Request? = synchronized(stateLock) {
        if (listOfNotNull(requested, active?.request).any {
                it.owner !== owner && it.priority > priority
            }) return@synchronized null
        requested?.takeIf { it.owner === owner && it.priority == priority }
            ?: Request(owner, priority).also { requested = it }
    }

    fun isCurrent(request: Request): Boolean = synchronized(stateLock) { requested === request }

    fun owns(request: Request): Boolean = synchronized(stateLock) { active?.request === request }

    fun cancel(owner: Any) = synchronized(stateLock) {
        if (requested?.owner === owner) requested = null
    }

    /** On the EDT, before starting a native worker. Stop outgoing UI commands first. */
    fun prepare(request: Request) {
        val previous = synchronized(stateLock) {
            if (requested !== request) return
            active?.takeIf { it.request.owner !== request.owner && !it.prepared }
        } ?: return
        previous.quiesce()
        synchronized(stateLock) { if (active === previous) previous.prepared = true }
    }

    /** Caller serializes this and native creation/disposal with lifecycleLock. */
    fun claim(
        request: Request,
        quiesce: () -> Unit = {},
        stillEligible: () -> Boolean = { true },
        release: () -> Unit,
    ): Boolean {
        check(Thread.holdsLock(lifecycleLock))
        // Prepared handles must be drained even if the incoming request was cancelled.
        val previous = synchronized(stateLock) { active?.takeIf { it.prepared } }
        if (previous != null) {
            previous.release() // Must finish successfully before another native create.
            synchronized(stateLock) { if (active === previous) active = null }
        }
        if (!stillEligible()) return false
        return synchronized(stateLock) {
            if (requested !== request) false else {
                check(active == null || active?.request?.owner === request.owner) {
                    "Outgoing Linux player must be unpublished before native handoff"
                }
                active = Active(request, quiesce, release)
                true
            }
        }
    }

    /** Only after native disposal (including cancelled/failed creation cleanup). */
    fun forget(owner: Any) {
        check(Thread.holdsLock(lifecycleLock))
        synchronized(stateLock) { if (active?.request?.owner === owner) active = null }
    }
}
