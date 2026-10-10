package com.nuvio.app.features.player.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LinuxPlayerOwnershipTest {
    private class Fixture {
        val lock = Any()
        val ownership = LinuxPlayerOwnership(lock)
        val live = mutableSetOf<Any>()
        val events = mutableListOf<String>()
        fun request(owner: String, priority: LinuxPlayerOwnership.Priority = LinuxPlayerOwnership.Priority.HERO) =
            assertNotNull(ownership.request(owner, priority))
        fun destroy(owner: Any) {
            assertTrue(Thread.holdsLock(lock))
            if (live.remove(owner)) events += "dispose $owner"
        }
        fun open(request: LinuxPlayerOwnership.Request, release: () -> Unit = { destroy(request.owner) }): Boolean {
            ownership.prepare(request)
            return synchronized(lock) {
                if (!ownership.claim(request, release = release)) return@synchronized false
                if (request.owner !in live) {
                    assertTrue(live.isEmpty(), "Native single-owner guard would reject this create")
                    live += request.owner
                    events += "create ${request.owner}"
                }
                true
            }
        }
        fun close(owner: String) {
            ownership.cancel(owner) // Invalidate before waiting for native lifecycle work.
            synchronized(lock) { destroy(owner); ownership.forget(owner) }
        }
    }

    @Test fun handoffDisposesBeforeCreating() {
        val f = Fixture()
        f.open(f.request("A"))
        f.open(f.request("B"))
        assertEquals(listOf("create A", "dispose A", "create B"), f.events)
    }

    @Test fun fullPlayerReplacesHeroAndCannotBePreemptedByHero() {
        val f = Fixture()
        f.open(f.request("hero"))
        f.open(f.request("player", LinuxPlayerOwnership.Priority.PLAYER))
        assertNull(f.ownership.request("another hero", LinuxPlayerOwnership.Priority.HERO))
        assertEquals(listOf("create hero", "dispose hero", "create player"), f.events)
    }

    @Test fun pendingFullPlayerAlsoHasPriority() {
        val f = Fixture()
        val hero = f.request("hero")
        val player = f.request("player", LinuxPlayerOwnership.Priority.PLAYER)
        assertNull(f.ownership.request("other hero", LinuxPlayerOwnership.Priority.HERO))
        assertFalse(f.open(hero))
        assertTrue(f.open(player))
        assertEquals(listOf("create player"), f.events)
    }

    @Test fun cancelledNavigationRequestCannotResurrectAfterPlayerExit() {
        val f = Fixture()
        val stale = f.request("hero")
        f.ownership.cancel("hero")
        f.open(f.request("player", LinuxPlayerOwnership.Priority.PLAYER))
        f.close("player")
        assertFalse(f.open(stale))
        assertTrue(f.live.isEmpty())
        // Only a fresh foreground request may restart the retained surface.
        assertTrue(f.open(f.request("hero")))
    }

    @Test fun retainedHiddenDetailsYieldsAndCanResumeWithFreshEligibility() {
        val f = Fixture()
        val details = f.request("details")
        f.open(details)
        f.close("details")
        f.open(f.request("home"))
        assertFalse(f.open(details))
        assertTrue(f.open(f.request("details")))
        assertEquals(listOf("create details", "dispose details", "create home", "dispose home", "create details"), f.events)
    }

    @Test fun repeatedSameOwnerRequestDoesNotChurnOwnership() {
        val f = Fixture()
        val request = f.request("hero")
        f.open(request)
        repeat(100) {
            assertSame(request, f.request("hero"))
            assertTrue(f.ownership.isCurrent(request))
        }
        assertEquals(listOf("create hero"), f.events)
    }

    @Test fun disposalFreesOwnershipAndIsIdempotent() {
        val f = Fixture()
        val player = f.request("player", LinuxPlayerOwnership.Priority.PLAYER)
        f.open(player)
        f.close("player")
        f.close("player")
        assertFalse(f.ownership.isCurrent(player))
        f.open(f.request("hero"))
        assertEquals(listOf("create player", "dispose player", "create hero"), f.events)
    }

    @Test fun latestPendingHeroWinsAndOldOwnerCancellationDoesNotCancelIt() {
        val f = Fixture()
        val first = f.request("first")
        val second = f.request("second")
        f.ownership.cancel("first")
        assertFalse(f.open(first))
        assertTrue(f.open(second))
        assertEquals(listOf("create second"), f.events)
    }

    @Test fun cancelledIncomingRequestLeavesTheLiveOutgoingOwnerIntact() {
        val f = Fixture()
        val hero = f.request("hero")
        f.open(hero)
        val incoming = f.request("player", LinuxPlayerOwnership.Priority.PLAYER)
        assertFalse(f.ownership.isCurrent(hero))
        assertTrue(f.ownership.owns(hero), "Live owner events continue until actual handoff")
        f.ownership.cancel("player")
        assertFalse(f.open(incoming))
        assertTrue(f.ownership.owns(hero))
        assertEquals(listOf("create hero"), f.events)
    }

    @Test fun evictedAndDisposedOwnersCannotDeliverEvents() {
        val f = Fixture()
        val first = f.request("first")
        f.open(first)
        val second = f.request("second")
        f.open(second)
        assertFalse(f.ownership.owns(first))
        assertTrue(f.ownership.owns(second))
        f.close("second")
        assertFalse(f.ownership.owns(second))
    }

    @Test fun outgoingUiIsUnpublishedBeforeDisposalAndCancelledIncomingStillDrains() {
        val f = Fixture()
        val first = f.request("A")
        synchronized(f.lock) {
            assertTrue(f.ownership.claim(first,
                quiesce = {
                    assertFalse(Thread.holdsLock(f.lock), "UI must not wait under the native lifecycle lock")
                    f.events += "unpublish A"
                },
                release = { f.destroy("A") },
            ))
            f.live += "A"
            f.events += "create A"
        }
        val incoming = f.request("B")
        f.ownership.prepare(incoming)
        f.ownership.cancel("B")
        synchronized(f.lock) {
            assertFalse(f.ownership.claim(incoming) { error("B never became owner") })
        }
        assertEquals(listOf("create A", "unpublish A", "dispose A"), f.events)
        assertTrue(f.live.isEmpty())
        assertTrue(f.open(f.request("C")))
    }

    @Test fun requestArrivingDuringDisposalInvalidatesTheIncomingCreate() {
        val f = Fixture()
        var latest: LinuxPlayerOwnership.Request? = null
        f.open(f.request("A")) {
            f.destroy("A")
            latest = f.request("C")
        }
        assertFalse(f.open(f.request("B")))
        assertEquals(listOf("create A", "dispose A"), f.events)
        assertTrue(f.open(assertNotNull(latest)))
        assertEquals(setOf<Any>("C"), f.live)
    }

    @Test fun cancellationDoesNotWaitForOutgoingNativeDisposal() {
        val f = Fixture()
        val disposing = CountDownLatch(1)
        val finish = CountDownLatch(1)
        f.open(f.request("A")) {
            disposing.countDown()
            check(finish.await(3, TimeUnit.SECONDS))
            f.destroy("A")
        }
        val request = f.request("B")
        val failure = AtomicReference<Throwable?>()
        val worker = thread {
            try { assertFalse(f.open(request)) } catch (error: Throwable) { failure.set(error) }
        }
        try {
            assertTrue(disposing.await(3, TimeUnit.SECONDS))
            // This finishes while the lifecycle lock is held by disposal. No state lock
            // is held across the native callback, and no ownership wait blocks cancel.
            f.ownership.cancel("B")
            assertFalse(f.ownership.isCurrent(request))
        } finally {
            finish.countDown()
            worker.join(4000)
        }
        assertFalse(worker.isAlive)
        failure.get()?.let { throw it }
        assertTrue(f.live.isEmpty())
    }

    @Test fun disposalFailureNeverAllowsAnotherNativeCreate() {
        val f = Fixture()
        f.open(f.request("A")) { error("native disposal failed") }
        assertFailsWith<IllegalStateException> { f.open(f.request("B")) }
        assertEquals(listOf("create A"), f.events)
        assertEquals(setOf<Any>("A"), f.live)
    }

    @Test fun requestDuringCreationInvalidatesCallbacksAndRequiresCleanupBeforeNextCreate() {
        val f = Fixture()
        val hero = f.request("hero")
        synchronized(f.lock) {
            assertTrue(f.ownership.claim(hero) { f.destroy("hero") })
            val player = f.request("player", LinuxPlayerOwnership.Priority.PLAYER)
            // Model a native create that returns after its ticket was superseded.
            f.live += "hero"
            f.events += "create hero"
            assertFalse(f.ownership.isCurrent(hero), "Old events/publication must be rejected")
            f.destroy("hero")
            f.ownership.forget("hero")
            assertTrue(f.open(player))
        }
        assertEquals(listOf("create hero", "dispose hero", "create player"), f.events)
    }

    @Test fun claimAndForgetRequireNativeLifecycleSerialization() {
        val f = Fixture()
        assertFailsWith<IllegalStateException> { f.ownership.claim(f.request("hero")) {} }
        assertFailsWith<IllegalStateException> { f.ownership.forget("hero") }
    }
}
