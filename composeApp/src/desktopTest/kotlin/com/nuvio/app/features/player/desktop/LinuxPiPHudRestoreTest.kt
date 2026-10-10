package com.nuvio.app.features.player.desktop

import androidx.compose.ui.window.WindowPlacement
import java.awt.Rectangle
import kotlin.test.*

class LinuxPiPHudRestoreTest {
    private class Task(val action: () -> Unit, var cancelled: Boolean = false)
    private class Fixture {
        val window = LinuxPictureInPictureTransitionTest.FakeWindow(Rectangle(0, 0, 1920, 1080), false)
        var clock = 0L
        var finished = 0
        var failed = 0
        val tasks = ArrayDeque<Task>()
        val restore = LinuxPiPHudRestore(window, WindowPlacement.Fullscreen, { clock }, { action ->
            val task = Task(action); tasks.addLast(task)
            val cancel: () -> Unit = { task.cancelled = true }; cancel
        }, { finished++ }, { failed++ })
        init { window.suppressHud(true); window.beginGeometry(); restore.start() }
        fun tick(stale: Boolean = false) { val task = tasks.removeFirst(); if (stale || !task.cancelled) task.action() }
        fun drain() { var limit = 140; while (tasks.isNotEmpty() && limit-- > 0) tick(); assertTrue(limit > 0) }
    }
    @Test fun restoreWaitsForFinalPlacementAndCanvasThenShowsOnce() {
        val f = Fixture(); f.window.restoreReady = false
        f.window.bounds = Rectangle(10, 10, 480, 270)
        repeat(4) { f.tick() }; assertTrue(f.window.suppressed); assertFalse(f.window.events.contains("hudSync"))
        f.window.bounds = Rectangle(0, 0, 1920, 1080); f.window.restoreReady = true
        f.drain(); assertEquals(1, f.finished); assertEquals(0, f.failed)
        assertEquals(1, f.window.events.count { it == "suppressed=false" })
        assertEquals(1, f.window.events.count { it == "hudSync" })
        assertEquals(f.window.canvasGeometry()!!.root, f.window.hudRoot)
    }
    @Test fun staleCanvasCannotRevealHud() {
        val f = Fixture()
        f.window.bounds = Rectangle(10, 10, 480, 270)
        f.window.staleCanvas = f.window.canvasGeometry()
        f.window.bounds = Rectangle(0, 0, 1920, 1080)
        f.window.adoptCanvas = false
        repeat(5) { f.tick() }; assertTrue(f.window.suppressed)
        f.window.adoptCanvas = true; f.drain(); assertEquals(1, f.finished)
    }
    @Test fun alignmentRequiresTwoLaterObservations() {
        val f = Fixture(); f.tick(); f.tick()
        assertTrue(f.window.events.contains("hudSync")); assertTrue(f.window.suppressed)
        f.tick(); assertTrue(f.window.suppressed)
        f.tick(); assertFalse(f.window.suppressed)
    }
    @Test fun alreadyAlignedHudDoesNotMove() {
        val f = Fixture(); f.window.hudRoot = Rectangle(f.window.canvasGeometry()!!.root)
        f.drain(); assertFalse(f.window.events.contains("hudSync")); assertEquals(1, f.finished)
    }
    @Test fun mismatchResetsObservationCountWithoutRepeatedMutation() {
        val f = Fixture(); f.tick(); f.tick(); f.tick()
        f.window.hudRoot = Rectangle(); f.tick()
        f.window.hudRoot = Rectangle(f.window.canvasGeometry()!!.root)
        f.tick(); assertTrue(f.window.suppressed); f.tick(); assertEquals(1, f.finished)
        assertEquals(1, f.window.events.count { it == "hudSync" })
    }
    @Test fun permanentlyStaleHudFailsBoundedlyAndStaysHidden() {
        val f = Fixture(); f.window.applyHud = false; f.drain()
        assertEquals(1, f.failed); assertEquals(0, f.finished); assertTrue(f.window.suppressed)
        assertEquals(1, f.window.events.count { it == "hudSync" })
    }
    @Test fun canvasChangesDuringSyncRequireNewStableTarget() {
        val f = Fixture()
        f.window.onHudSync = { f.window.bounds = Rectangle(1, 0, 1920, 1080); f.window.onHudSync = {} }
        f.drain(); assertEquals(1, f.finished); assertEquals(2, f.window.events.count { it == "hudSync" })
    }
    @Test fun staleNativeRequestDoesNotConsumeMutationOrReveal() {
        val f = Fixture(); f.window.requestStale = true; f.tick(); f.tick()
        assertTrue(f.window.suppressed); assertFalse(f.window.events.contains("hudSync"))
        f.window.requestStale = false; f.drain(); assertEquals(1, f.finished)
    }
    @Test fun nativeFailureStaysHiddenWithoutRetry() {
        val f = Fixture(); f.window.requestFails = true; f.drain()
        assertEquals(1, f.failed); assertTrue(f.window.suppressed); assertFalse(f.window.events.contains("hudSync"))
    }
    @Test fun expiredNativeObservationCannotReveal() {
        val f = Fixture(); f.window.onHudObserve = { f.clock = 3_000_000_000 }
        f.drain(); assertEquals(1, f.failed); assertTrue(f.window.suppressed)
    }
    @Test fun movingTargetsHaveBoundedMutationBudget() {
        val f = Fixture(); f.window.onHudSync = { f.window.bounds = Rectangle(f.window.bounds).apply { x++ } }
        f.drain(); assertEquals(1, f.failed); assertEquals(8, f.window.events.count { it == "hudSync" })
    }
    @Test fun disposalAndReentryCancelQueuedRestore() {
        val f = Fixture(); f.restore.close(); val before = f.window.events.toList()
        f.tick(stale = true); assertEquals(before, f.window.events); assertEquals(0, f.finished)
    }
}
