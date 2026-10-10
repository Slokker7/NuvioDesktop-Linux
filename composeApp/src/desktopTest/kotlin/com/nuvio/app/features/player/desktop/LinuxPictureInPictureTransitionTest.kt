package com.nuvio.app.features.player.desktop

import androidx.compose.ui.window.WindowPlacement
import java.awt.Frame
import java.awt.Insets
import java.awt.Rectangle
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxPictureInPictureTransitionTest {
    @Test fun requestedFloatingDoesNotApplyPiPWhileLiveWindowIsFullscreen() = onEdt {
        val fixture = Fixture()
        fixture.transition.setActive(true)
        assertEquals(WindowPlacement.Floating, fixture.window.requestedPlacement)
        assertEquals(WindowPlacement.Fullscreen, fixture.window.livePlacement)
        assertTrue(fixture.window.ownsFullscreen)
        assertEquals(1, fixture.pending)
        fixture.assertNoPiPMutations()
    }

    @Test fun liveFloatingStillWaitsForFullscreenOwnershipToClear() = onEdt {
        val fixture = Fixture()
        fixture.transition.setActive(true)
        fixture.window.livePlacement = WindowPlacement.Floating
        fixture.window.windowManagerFloating = true
        fixture.tick()
        fixture.assertNoPiPMutations()
        fixture.window.ownsFullscreen = false
        fixture.tick()
        fixture.tick()
        assertEquals(fixture.pipBounds, fixture.window.bounds)
        assertFalse(fixture.window.input)
        fixture.finishEntry()
        assertEquals(0, fixture.pending)
    }

    @Test fun clearedOwnershipStillWaitsForLiveFloatingPlacement() = onEdt {
        val fixture = Fixture()
        fixture.transition.setActive(true)
        fixture.window.ownsFullscreen = false
        fixture.window.windowManagerFloating = true
        fixture.tick()
        fixture.assertNoPiPMutations()
        fixture.release()
        fixture.tick()
        fixture.tick()
        assertEquals(fixture.pipBounds, fixture.window.bounds)
    }

    @Test fun settlementAppliesChromeTopmostBoundsAndFrontInOrder() = onEdt {
        val fixture = Fixture()
        fixture.transition.setActive(true)
        fixture.release()
        fixture.tick()
        assertFalse(fixture.window.events.any { it.startsWith("bounds=") })
        fixture.tick()
        assertEquals(listOf("chrome=true", "topmost=true", "bounds=${fixture.pipBounds}", "sync", "front"),
            fixture.window.events.takeLast(5))
        assertEquals(fixture.pipBounds, fixture.window.requestedBounds)
        assertTrue(fixture.owned)
    }

    @Test fun floatingWaitsForOldAwtGeometryWritebackBeforeApplyingPiP() = onEdt {
        val fixture = Fixture()
        fixture.transition.setActive(true)
        fixture.release()
        fixture.window.geometrySynchronized = false
        fixture.tick()
        fixture.assertNoPiPMutations()
        fixture.window.geometrySynchronized = true
        fixture.tick()
        fixture.tick()
        assertEquals(fixture.pipBounds, fixture.window.bounds)
    }

    @Test fun javaFloatingStillWaitsForWindowManagerFullscreenAcknowledgement() = onEdt {
        val fixture = Fixture()
        fixture.transition.setActive(true)
        fixture.release()
        fixture.window.windowManagerFloating = false
        fixture.tick()
        fixture.assertNoPiPMutations()
        fixture.window.windowManagerFloating = true
        fixture.tick()
        fixture.tick()
        assertEquals(fixture.pipBounds, fixture.window.bounds)
    }

    @Test fun geometryChangedDuringWindowManagerQueryMustSettleBeforeAcceptance() = onEdt {
        val fixture = Fixture()
        fixture.transition.setActive(true)
        fixture.release()
        fixture.window.onWindowManagerQuery = { fixture.window.geometrySynchronized = false }
        fixture.tick()
        fixture.assertNoPiPMutations()
        fixture.window.onWindowManagerQuery = {}
        fixture.window.geometrySynchronized = true
        fixture.tick()
        fixture.tick()
        assertEquals(fixture.pipBounds, fixture.window.bounds)
    }

    @Test fun chromeInsetRecoveryMustSettleBeforeTopmostAndPiPBounds() = onEdt {
        val fixture = Fixture()
        fixture.window.chromeGeometrySynchronized = false
        fixture.transition.setActive(true)
        fixture.release()
        fixture.tick()
        assertEquals(1, fixture.window.events.count { it == "chrome=true" })
        assertFalse(fixture.window.events.any { it.startsWith("bounds=") || it.startsWith("topmost=") || it == "front" })
        fixture.tick()
        assertEquals(1, fixture.window.events.count { it == "chrome=true" })
        fixture.window.chromeGeometrySynchronized = true
        fixture.tick()
        assertFalse(fixture.window.events.any { it.startsWith("bounds=") })
        fixture.tick()
        assertEquals(fixture.pipBounds, fixture.window.bounds)
    }

    @Test fun lateGeometryChangeMustStayStableAcrossAnEdtTurnBeforePiP() = onEdt {
        val fixture = Fixture()
        fixture.transition.setActive(true)
        fixture.release()
        fixture.tick()
        fixture.window.bounds = Rectangle(80, 60, 800, 600)
        fixture.window.events.clear()
        fixture.tick()
        assertFalse(fixture.window.events.any { it.startsWith("bounds=") || it.startsWith("topmost=") })
        fixture.tick()
        assertEquals(fixture.pipBounds, fixture.window.bounds)
    }

    @Test fun lossOfReadinessInvalidatesThePreviousStableGeometryObservation() = onEdt {
        val fixture = Fixture()
        fixture.transition.setActive(true)
        fixture.release()
        fixture.tick()
        fixture.window.geometrySynchronized = false
        fixture.tick()
        fixture.window.geometrySynchronized = true
        fixture.tick()
        assertFalse(fixture.window.events.any { it.startsWith("bounds=") || it.startsWith("topmost=") })
        fixture.tick()
        assertEquals(fixture.pipBounds, fixture.window.bounds)
    }

    @Test fun exitDuringChromeSettlementRestoresChromeAndCancelsLateGeometry() = onEdt {
        val fixture = Fixture()
        fixture.window.chromeGeometrySynchronized = false
        fixture.transition.setActive(true)
        fixture.release()
        fixture.tick()
        fixture.transition.setActive(false)
        val events = fixture.window.events.toList()
        fixture.tick(evenIfCancelled = true)
        assertEquals(events, fixture.window.events)
        assertEquals(listOf("chrome=true", "chrome=false"), events.filter { it.startsWith("chrome=") })
        assertFalse(events.contains("bounds=${fixture.pipBounds}"))
        assertFalse(events.contains("topmost=true"))
        assertTrue(fixture.owned)
        fixture.finishEntry()
        assertFalse(fixture.owned)
    }

    @Test fun chromeSettlementSharesTheOriginalDeadlineAndRollsBackOnTimeout() = onEdt {
        val fixture = Fixture()
        fixture.window.chromeGeometrySynchronized = false
        fixture.transition.setActive(true)
        fixture.release()
        fixture.tick()
        fixture.clock = 2_000_000_000L
        fixture.tick()
        assertEquals(1, fixture.rejections)
        fixture.finishEntry()
        assertEquals(0, fixture.pending)
        assertEquals(listOf("chrome=true", "chrome=false"), fixture.window.events.filter { it.startsWith("chrome=") })
        assertFalse(fixture.window.events.contains("bounds=${fixture.pipBounds}"))
        assertEquals(WindowPlacement.Fullscreen, fixture.window.livePlacement)
    }

    @Test fun readinessCheckBudgetIsSharedAcrossFloatingAndChromeSettlement() = onEdt {
        val fixture = Fixture()
        fixture.window.chromeGeometrySynchronized = false
        fixture.transition.setActive(true)
        repeat(125) { fixture.tick() }
        fixture.release()
        fixture.tick()
        assertEquals(1, fixture.pending)
        fixture.tick()
        assertEquals(1, fixture.rejections)
        fixture.finishEntry()
        assertEquals(0, fixture.pending)
        assertTrue(fixture.tasksCreated <= 134)
        assertFalse(fixture.window.events.contains("bounds=${fixture.pipBounds}"))
    }

    @Test fun acceptanceRepairsWritebackThatOccurredWhileFullscreenWasPending() = onEdt {
        val fixture = Fixture()
        fixture.transition.setActive(true)
        fixture.window.requestedPlacement = WindowPlacement.Fullscreen
        fixture.release()
        fixture.tick()
        fixture.tick()
        assertEquals(WindowPlacement.Floating, fixture.window.requestedPlacement)
        assertEquals(WindowPlacement.Floating, fixture.window.livePlacement)
        assertFalse(fixture.window.ownsFullscreen)
        fixture.finishEntry()
        assertEquals(0, fixture.pending)
    }

    @Test fun alreadyFloatingAppliesBoundsButStillWaitsForFinalCanvasAndHud() = onEdt {
        val fixture = Fixture(floating = true)
        fixture.transition.setActive(true)
        assertEquals(fixture.pipBounds, fixture.window.bounds)
        assertEquals(1, fixture.pending)
        assertFalse(fixture.window.input)
        fixture.finishEntry()
        assertEquals(0, fixture.pending)
    }

    @Test fun exitCancelsPendingEntryAndEvenAnAlreadyQueuedCallbackIsHarmless() = onEdt {
        val fixture = Fixture()
        fixture.transition.setActive(true)
        fixture.transition.setActive(false)
        val events = fixture.window.events.toList()
        fixture.tick(evenIfCancelled = true)
        assertEquals(events, fixture.window.events)
        fixture.assertNoPiPMutations()
        assertEquals(WindowPlacement.Fullscreen, fixture.window.livePlacement)
        assertTrue(fixture.owned)
        fixture.finishEntry()
        assertFalse(fixture.owned)
        assertTrue(fixture.remembered.isEmpty())
    }

    @Test fun disposalCancelsPendingEntryWithoutAnyLateWindowMutation() = onEdt {
        val fixture = Fixture()
        fixture.transition.setActive(true)
        fixture.window.displayable = false
        fixture.transition.close()
        val events = fixture.window.events.toList()
        fixture.tick(evenIfCancelled = true)
        fixture.transition.setActive(true)
        fixture.transition.setActive(false)
        fixture.transition.close()
        assertEquals(events, fixture.window.events)
        assertFalse(fixture.owned)
        assertEquals(0, fixture.pending)
    }

    @Test fun repeatedEntryKeepsOneWorkerAndOneRestoreSnapshot() = onEdt {
        val fixture = Fixture()
        repeat(5) { fixture.transition.setActive(true) }
        assertEquals(1, fixture.pending)
        assertEquals(1, fixture.window.events.count { it == "live=Floating" })
        fixture.release()
        fixture.tick()
        fixture.tick()
        repeat(5) { fixture.transition.setActive(true) }
        assertEquals(1, fixture.window.events.count { it == "chrome=true" })
        fixture.transition.setActive(false)
        assertEquals(WindowPlacement.Fullscreen, fixture.window.livePlacement)
    }

    @Test fun oldGenerationCannotCompleteOrCancelANewerEntry() = onEdt {
        val fixture = Fixture()
        fixture.transition.setActive(true)
        fixture.transition.setActive(false)
        fixture.transition.setActive(true)
        fixture.release()
        fixture.tick(evenIfCancelled = true)
        fixture.assertNoPiPMutations()
        assertEquals(1, fixture.pending)
        fixture.tick()
        assertEquals(1, fixture.window.events.count { it == "chrome=true" })
    }

    @Test fun readinessTimeoutRollsBackAndStopsScheduling() = onEdt {
        val fixture = Fixture()
        fixture.transition.setActive(true)
        fixture.clock = 2_000_000_000L
        fixture.tick()
        assertEquals(1, fixture.rejections)
        fixture.finishEntry()
        assertEquals(0, fixture.pending)
        assertEquals(WindowPlacement.Fullscreen, fixture.window.requestedPlacement)
        assertFalse(fixture.owned)
        fixture.assertNoPiPMutations()
    }

    @Test fun readinessCheckBudgetIsBoundedEvenIfClockDoesNotAdvance() = onEdt {
        val fixture = Fixture()
        fixture.transition.setActive(true)
        repeat(127) { fixture.tick() }
        assertEquals(1, fixture.rejections)
        fixture.finishEntry()
        assertEquals(0, fixture.pending)
        assertTrue(fixture.tasksCreated <= 134)
        fixture.assertNoPiPMutations()
    }

    @Test fun fullscreenExitRestoresChromeTopmostAndFullscreenAfterGeometry() = onEdt {
        val fixture = Fixture()
        fixture.transition.setActive(true)
        fixture.release()
        fixture.tick()
        fixture.tick()
        fixture.finishEntry()
        fixture.window.events.clear()
        fixture.transition.setActive(false)
        assertEquals(listOf(fixture.pipBounds), fixture.remembered)
        assertEquals(fixture.originalBounds, fixture.window.bounds)
        assertEquals(WindowPlacement.Fullscreen, fixture.window.requestedPlacement)
        assertEquals(WindowPlacement.Fullscreen, fixture.window.livePlacement)
        assertTrue(fixture.window.ownsFullscreen)
        assertFalse(fixture.window.alwaysOnTop)
        assertTrue(fixture.fullscreen)
        assertEquals("chrome=false", fixture.window.events.first())
        val fullscreenIndex = fixture.window.events.indexOf("live=Fullscreen")
        assertTrue(fullscreenIndex > fixture.window.events.indexOf("bounds=${fixture.originalBounds}"))
        assertFalse(fixture.window.events.drop(fullscreenIndex + 1).any { it.startsWith("bounds=") || it == "sync" })
    }

    @Test fun floatingExitRestoresExactBoundsExtendedStateAndOriginalTopmost() = onEdt {
        val fixture = Fixture(floating = true)
        fixture.window.alwaysOnTop = true
        fixture.window.extendedState = Frame.NORMAL
        fixture.transition.setActive(true)
        fixture.transition.setActive(false)
        assertEquals(fixture.originalBounds, fixture.window.bounds)
        assertEquals(fixture.originalBounds, fixture.window.requestedBounds)
        assertEquals(WindowPlacement.Floating, fixture.window.requestedPlacement)
        assertEquals(WindowPlacement.Floating, fixture.window.livePlacement)
        assertEquals(Frame.NORMAL, fixture.window.extendedState)
        assertTrue(fixture.window.alwaysOnTop)
        assertFalse(fixture.fullscreen)
        assertEquals(listOf("chrome=true", "chrome=false"), fixture.window.events.filter { it.startsWith("chrome=") })
    }

    @Test fun ordinaryGeometryIsNotReleasedUntilRestoreStateIsSynchronized() = onEdt {
        val fixture = Fixture(floating = true)
        fixture.pipBounds = Rectangle(100, 100, 800, 600)
        fixture.transition.setActive(true)
        assertTrue(fixture.owned)
        fixture.transition.setActive(false)
        assertTrue(fixture.releasedGeometry.isEmpty())
        fixture.finishEntry()
        assertEquals(fixture.originalBounds, fixture.releasedGeometry.single())
    }

    @Test fun peerDisappearanceDuringReadinessStopsWithoutWindowMutation() = onEdt {
        val fixture = Fixture()
        fixture.transition.setActive(true)
        fixture.window.displayable = false
        val events = fixture.window.events.toList()
        fixture.tick()
        assertEquals(events, fixture.window.events)
        assertEquals(0, fixture.pending)
        assertEquals(1, fixture.rejections)
        assertFalse(fixture.owned)
    }

    private class Task(val action: () -> Unit, var cancelled: Boolean = false)

    @Test fun retainedAndAbsentDecorationBothReachPlayable480By270WithoutTouchingTheHiddenHud() = onEdt {
        for (loss in listOf(Insets(0, 0, 0, 0), Insets(37, 0, 0, 0), Insets(17, 3, 11, 9))) {
            val fixture = Fixture(floating = true)
            fixture.window.contentInsets = loss
            fixture.transition.setActive(true)
            assertEquals(480 + loss.left + loss.right, fixture.window.bounds.width)
            assertEquals(270 + loss.top + loss.bottom, fixture.window.bounds.height)
            assertFalse(fixture.window.input)
            fixture.finishEntry()
            assertEquals(480, fixture.window.canvasGeometry()!!.component.width)
            assertEquals(270, fixture.window.canvasGeometry()!!.component.height)
            assertFalse(fixture.window.events.contains("hudSync"))
            assertTrue(fixture.window.input)
            assertEquals(0, fixture.rejections)
            fixture.transition.setActive(false)
            assertEquals(listOf(fixture.pipBounds), fixture.remembered)
        }
    }

    @Test fun oldCanvasAfterFinalOuterResizeCannotCompleteOrSynchronizeTheHud() = onEdt {
        val fixture = Fixture(floating = true)
        val old = fixture.window.canvasGeometry()
        fixture.transition.setActive(true)
        fixture.window.staleCanvas = old
        fixture.window.adoptCanvas = false
        repeat(4) { fixture.tick() }
        assertFalse(fixture.window.input)
        assertFalse(fixture.window.events.contains("hudSync"))
        fixture.window.adoptCanvas = true
        fixture.finishEntry()
        assertTrue(fixture.window.input)
    }

    @Test fun lateDecorationRemovalRecomputesFromContentExactlyOnceWithoutDrift() = onEdt {
        val fixture = Fixture(floating = true)
        fixture.window.contentInsets = Insets(37, 0, 0, 0)
        fixture.transition.setActive(true)
        fixture.finishEntry()
        assertEquals(307, fixture.window.bounds.height)
        val assignments = fixture.window.events.count { it.startsWith("bounds=") }
        fixture.window.contentInsets = Insets(0, 0, 0, 0)
        fixture.window.geometryChanged()
        assertFalse(fixture.window.input)
        fixture.finishEntry()
        assertEquals(fixture.pipBounds, fixture.window.bounds)
        assertEquals(assignments + 1, fixture.window.events.count { it.startsWith("bounds=") })
        repeat(5) { fixture.window.geometryChanged() }
        assertEquals(0, fixture.pending)
        assertEquals(270, fixture.window.canvasGeometry()!!.component.height)
        assertFalse(fixture.window.events.contains("hudSync"))
    }

    @Test fun decorationRemovalDuringFinalSettlementDoesNotAccumulateCompensation() = onEdt {
        val fixture = Fixture(floating = true)
        fixture.window.contentInsets = Insets(37, 0, 0, 0)
        fixture.transition.setActive(true)
        fixture.tick()
        fixture.window.contentInsets = Insets(0, 0, 0, 0)
        fixture.finishEntry()
        assertEquals(fixture.pipBounds, fixture.window.bounds)
        assertEquals(2, fixture.window.events.count { it.startsWith("bounds=") })
    }

    @Test fun repeatedDecoratedEnterExitRemembersContentWithoutGrowth() = onEdt {
        val fixture = Fixture(floating = true)
        fixture.window.contentInsets = Insets(37, 4, 6, 8)
        repeat(5) {
            fixture.transition.setActive(true)
            fixture.finishEntry()
            assertEquals(492, fixture.window.bounds.width)
            assertEquals(313, fixture.window.bounds.height)
            fixture.transition.setActive(false)
            fixture.pipBounds = Rectangle(fixture.remembered.last())
            assertEquals(Rectangle(1420, 790, 480, 270), fixture.pipBounds)
        }
    }

    @Test fun exitCancelsFinalSettlementAndAlreadyQueuedHudSync() = onEdt {
        val fixture = Fixture(floating = true)
        fixture.transition.setActive(true)
        fixture.tick()
        fixture.transition.setActive(false)
        val events = fixture.window.events.toList()
        fixture.tick(evenIfCancelled = true)
        assertEquals(events, fixture.window.events)
        fixture.window.geometryChanged()
        fixture.finishEntry()
        assertEquals(0, fixture.pending)
    }

    @Test fun finalCanvasAndHudShareTheOriginalTimeoutAndRollbackFullscreen() = onEdt {
        val fixture = Fixture()
        fixture.transition.setActive(true)
        fixture.release()
        fixture.tick(); fixture.tick()
        fixture.clock = 2_000_000_000L
        fixture.tick()
        assertEquals(1, fixture.rejections)
        fixture.finishEntry()
        assertEquals(0, fixture.pending)
        assertEquals(WindowPlacement.Fullscreen, fixture.window.livePlacement)
        assertEquals(fixture.originalBounds, fixture.window.bounds)
    }

    @Test fun activeStateClearedBeforeTheHandlerPreventsFinalHudSync() = onEdt {
        val fixture = Fixture(floating = true)
        fixture.transition.setActive(true)
        fixture.tick()
        fixture.active = false
        fixture.tick()
        assertFalse(fixture.window.events.contains("hudSync"))
        assertFalse(fixture.window.input)
        fixture.transition.setActive(false)
    }

    @Test fun outerCompensationRespectsNegativeMonitorWorkAreaAndAllEdges() {
        val work = Rectangle(-1920, -1080, 1920, 1040)
        val target = Rectangle(-500, -330, 480, 270)
        val loss = Insets(37, 4, 6, 8)
        val outer = linuxPiPOuterBounds(target, loss, work)
        assertEquals(Rectangle(-512, -373, 492, 313), outer)
        assertTrue(work.contains(outer))
        val offscreen = linuxPiPOuterBounds(Rectangle(-2000, -1200, 480, 270), loss, work)
        assertTrue(work.contains(offscreen))
        val small = linuxPiPOuterBounds(target, loss, Rectangle(-300, -200, 300, 200))
        assertTrue(Rectangle(-300, -200, 300, 200).contains(small))
    }

    @Test fun alternatingLateInsetsStopWithBoundedWorkAndKeepPiP() = onEdt {
        val f = Fixture(floating = true)
        f.window.contentInsets = Insets(37, 0, 0, 0)
        f.transition.setActive(true)
        f.finishEntry()
        f.window.hudFollowsCanvas = true // Periodic/native follow can naturally align revisited targets.
        repeat(160) { index ->
            f.clock += 3_000_000_000L
            f.window.contentInsets = Insets(if (index % 2 == 0) 0 else 37, 0, 0, 0)
            f.window.geometryChanged()
            f.finishEntry()
        }
        assertEquals(0, f.rejections)
        assertTrue(f.owned)
        assertTrue(f.window.alwaysOnTop)
        assertTrue(f.tasksCreated <= 132)
        assertEquals(5, f.window.events.count { it.startsWith("bounds=") })
        assertTrue(f.window.events.count { it == "hudSync" } <= 8)
        val events = f.window.events.toList()
        repeat(160) { f.window.geometryChanged() }
        assertEquals(events, f.window.events)
        assertEquals(0, f.pending)
        // A new entry alone resets the safety budgets.
        f.transition.setActive(false)
        f.transition.setActive(true)
        f.finishEntry()
        assertTrue(f.window.input)
    }

    @Test fun recoveryChecksAreBoundedEvenIfInsetsNeverSettle() = onEdt {
        val f = Fixture(floating = true)
        f.transition.setActive(true)
        f.finishEntry()
        f.window.contentInsets = Insets(37, 0, 0, 0)
        f.window.geometryChanged()
        var checks = 0
        while (f.pending > 0 && checks < 160) {
            f.window.contentInsets = Insets(if (checks++ % 2 == 0) 37 else 1, 0, 0, 0)
            f.tick()
        }
        assertTrue(checks <= 128)
        assertEquals(0, f.pending)
        assertEquals(0, f.rejections)
        assertTrue(f.owned)
        assertEquals(1, f.window.events.count { it.startsWith("bounds=") })
    }

    @Test fun hudSuppressionIsOnceBeforeAnyWindowMutationAndInputWaitsForSettlement() = onEdt {
        val f = Fixture(floating = true)
        repeat(3) { f.transition.setActive(true) }
        assertEquals("suppressed=true", f.window.events.first())
        assertEquals(1, f.window.events.count { it == "suppressed=true" })
        assertFalse(f.window.input)
        f.finishEntry()
        assertTrue(f.window.input)
        assertFalse(f.window.events.contains("hudSync"))
    }

    @Test fun explicitDoubleClickExitOverridesWindowedRestoreAndWaitsBeforeReveal() = onEdt {
        val f = Fixture(floating = true)
        f.transition.setActive(true); f.finishEntry()
        f.window.restoreReady = false
        f.transition.requestExitToFullscreen(); f.transition.setActive(false)
        assertFalse(f.window.input); assertTrue(f.window.suppressed); assertTrue(f.owned)
        assertEquals(WindowPlacement.Fullscreen, f.window.requestedPlacement)
        f.window.bounds = Rectangle(0, 0, 1920, 1080); f.window.syncGeometry(); f.window.restoreReady = true
        f.finishEntry()
        assertFalse(f.window.suppressed); assertFalse(f.owned)
        assertEquals(1, f.window.events.count { it == "suppressed=false" })
    }

    @Test fun hudlessEntryAndExitUseDefaultConfiguration() = onEdt {
        val f = Fixture(floating = true)
        f.transition.setActive(true); f.finishEntry()
        assertTrue(f.window.suppressed); assertTrue(f.window.input)
        assertEquals(480, f.window.canvasGeometry()!!.component.width)
        assertEquals(270, f.window.canvasGeometry()!!.component.height)
        f.transition.setActive(false); f.finishEntry()
        assertFalse(f.window.suppressed); assertFalse(f.window.input)
    }

    @Test fun ordinaryDragMovementDoesNotPollGtkOrResizeAndRemembersFinalPosition() = onEdt {
        val f = Fixture(floating = true)
        f.transition.setActive(true); f.finishEntry()
        val reads = f.window.canvasReads
        repeat(30) {
            f.window.bounds = Rectangle(f.window.bounds).apply { x -= 1; y -= 1 }
            f.window.syncGeometry()
            f.window.geometryChanged()
        }
        assertEquals(reads, f.window.canvasReads)
        assertEquals(0, f.pending)
        assertEquals(480, f.window.bounds.width); assertEquals(270, f.window.bounds.height)
        val moved = Rectangle(f.window.bounds)
        f.transition.setActive(false)
        assertEquals(moved, f.remembered.single())
    }

    private class Fixture(floating: Boolean = false) {
        val originalBounds = if (floating) Rectangle(80, 60, 1280, 820) else Rectangle(0, 0, 1920, 1080)
        var pipBounds = Rectangle(1420, 790, 480, 270)
        val window = FakeWindow(originalBounds, floating)
        val tasks = ArrayDeque<Task>()
        val remembered = mutableListOf<Rectangle>()
        val releasedGeometry = mutableListOf<Rectangle>()
        var owned = false
        var fullscreen = !floating
        var clock = 0L
        var rejections = 0
        var tasksCreated = 0
        var active = true
        val pending get() = tasks.count { !it.cancelled }
        val transition = LinuxPictureInPictureTransition(window, { Rectangle(pipBounds) },
            { remembered.add(Rectangle(it)) }, { fullscreen = it }, {
                owned = it
                if (!it) releasedGeometry.add(Rectangle(window.requestedBounds))
            }, { rejections++ }, isActive = { active }, now = { clock }, schedule = { action ->
                val task = Task(action)
                tasks.addLast(task)
                tasksCreated++
                val cancel: () -> Unit = { task.cancelled = true }
                cancel
            })
        fun tick(evenIfCancelled: Boolean = false) {
            var task = tasks.removeFirst()
            while (task.cancelled && !evenIfCancelled && tasks.isNotEmpty()) task = tasks.removeFirst()
            if (!task.cancelled || evenIfCancelled) task.action()
        }
        fun release() {
            window.livePlacement = WindowPlacement.Floating
            window.ownsFullscreen = false
            window.windowManagerFloating = true
        }
        fun finishEntry() {
            var remaining = 300
            while (pending > 0 && remaining-- > 0) tick()
            assertTrue(remaining > 0)
        }
        fun assertNoPiPMutations() {
            assertFalse(window.events.any { it.startsWith("bounds=") || it.startsWith("chrome=") || it.startsWith("topmost=") })
        }
    }

    internal class FakeWindow(initialBounds: Rectangle, floating: Boolean) : LinuxPictureInPictureWindow {
        val events = mutableListOf<String>()
        var suppressed = false
        var input = false
        var restoreReady = true
        override fun suppressHud(value: Boolean): Boolean {
            if (suppressed != value) { suppressed = value; events.add("suppressed=$value") }
            return true
        }
        override fun interactive(value: Boolean) { input = value }
        override fun restoredPlacementReady(placement: WindowPlacement) = restoreReady && livePlacement == placement
        override var displayable = true
        override var livePlacement = if (floating) WindowPlacement.Floating else WindowPlacement.Fullscreen
        override var ownsFullscreen = !floating
        override var geometrySynchronized = true
        override var chromeGeometrySynchronized = true
        var onWindowManagerQuery: () -> Unit = {}
        override var windowManagerFloating = floating
            get() { onWindowManagerQuery(); return field }
        override var requestedPlacement = livePlacement
        var requestedBounds = Rectangle(initialBounds)
        override var bounds = Rectangle(initialBounds)
            set(value) { field = Rectangle(value); events.add("bounds=$value") }
        override var extendedState = Frame.NORMAL
        override var alwaysOnTop = false
            set(value) { field = value; events.add("topmost=$value") }
        override fun placeLive(placement: WindowPlacement) {
            events.add("live=$placement")
            if (placement != WindowPlacement.Floating) {
                livePlacement = placement
                ownsFullscreen = placement == WindowPlacement.Fullscreen
                windowManagerFloating = placement == WindowPlacement.Floating
            }
        }
        override fun syncGeometry() { requestedBounds = Rectangle(bounds); events.add("sync") }
        override fun compact(enabled: Boolean) { events.add("chrome=$enabled") }
        override fun toFront() { events.add("front") }
        override var contentInsets = Insets(0, 0, 0, 0)
        var adoptCanvas = true
        var staleCanvas: LinuxPiPCanvasGeometry? = null
        var applyHud = true
        var onHudSync: () -> Unit = {}
        var hudRoot = Rectangle()
        var geometryChanged: () -> Unit = {}
        var canvasReads = 0
        override fun canvasGeometry(): LinuxPiPCanvasGeometry? {
            canvasReads++
            return if (!adoptCanvas) staleCanvas else {
            val loss = contentInsets
            val component = Rectangle(0, 0, bounds.width - loss.left - loss.right, bounds.height - loss.top - loss.bottom)
            LinuxPiPCanvasGeometry(123, component, Rectangle(bounds.x, bounds.y, component.width, component.height),
                Rectangle(bounds), loss.clone() as Insets)
            }
        }
        override fun workArea(target: Rectangle) = Rectangle(0, 0, 1920, 1080)
        var geometryActive = false
        var requestFails = false
        var requestStale = false
        var hudFollowsCanvas = false
        var onHudObserve: () -> Unit = {}
        override fun beginGeometry() { geometryActive = true }
        override fun cancelGeometry() { geometryActive = false }
        override fun syncHud(geometry: LinuxPiPCanvasGeometry, remainingNanos: Long): LinuxPiPHudRequest {
            if (!geometryActive || requestFails) return LinuxPiPHudRequest.FAILED
            if (requestStale) return LinuxPiPHudRequest.STALE
            if (hudRoot == geometry.root) return LinuxPiPHudRequest.ALIGNED
            events.add("hudSync")
            if (applyHud) hudRoot = Rectangle(geometry.root)
            onHudSync()
            return LinuxPiPHudRequest.APPLIED
        }
        override fun hudAligned(geometry: LinuxPiPCanvasGeometry): Boolean {
            events.add("hudObserve")
            onHudObserve()
            if (hudFollowsCanvas) hudRoot = Rectangle(canvasGeometry()!!.root)
            return geometryActive && canvasGeometry()?.root == geometry.root && hudRoot == geometry.root
        }
        override fun observeGeometry(changed: () -> Unit): () -> Unit {
            geometryChanged = changed
            return { geometryChanged = {} }
        }
    }

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
