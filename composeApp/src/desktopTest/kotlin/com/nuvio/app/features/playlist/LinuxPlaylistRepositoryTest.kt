package com.nuvio.app.features.playlist

import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LinuxPlaylistRepositoryTest {
    private fun probe(case: String) = withHeadlessFixture { runHeadlessProbe(it, LinuxPlaylistRepositoryProbe::class.java, case) }
    @Test fun createAndEditMetadata() = probe("metadata")
    @Test fun addDeduplicatesVideosButKeepsRandomSlots() = probe("add")
    @Test fun reorderFollowsResumeEntry() = probe("reorder")
    @Test fun removalKeepsResumeConsistent() = probe("remove")
    @Test fun repositorySurvivesFreshProcess() = withHeadlessFixture {
        runHeadlessProbe(it, LinuxPlaylistRepositoryProbe::class.java, "write")
        runHeadlessProbe(it, LinuxPlaylistRepositoryProbe::class.java, "read")
    }
    @Test fun incompleteProgressRetainsResumeAndClearsSession() = probe("incomplete")
    @Test fun completedProgressAdvancesResume() = probe("progress")
    @Test fun completionLooksUpNextBeforeRemovingCurrent() = probe("finish")
    @Test fun loopWrapsAndNonLoopStops() = probe("loop")
    @Test fun randomSlotSurvivesRemoveWatched() = probe("random")
    @Test fun deletionReleasesSession() = probe("delete")
}

internal object LinuxPlaylistRepositoryProbe {
    private fun entry(id: String, random: Boolean = false) = PlaylistEntry(
        entryId = "input-$id", type = "movie", videoId = id, parentMetaId = id,
        parentMetaType = "movie", title = "Title $id", randomEpisode = random,
    )

    @JvmStatic fun main(args: Array<String>) {
        val root = Path.of(args[0])
        val scenario = args[1]
        if (scenario == "read") {
            PlaylistRepository.ensureLoaded()
            val playlist = PlaylistRepository.playlists.value.single()
            assertEquals(Files.readString(root.resolve("playlist-id")), playlist.id)
            assertEquals("Saved", playlist.name)
            assertEquals(listOf("c", "a", "b"), playlist.entries.map { it.videoId })
            assertEquals("b", playlist.resumeEntry?.videoId)
            assertTrue(playlist.loop)
            assertTrue(playlist.removeWatched)
            assertFalse(playlist.preferLocalLibrary)
            assertNull(PlaylistPlaybackSession.current())
            return
        }
        val playlist = PlaylistRepository.create("  Initial  ")
        val id = playlist.id
        PlaylistRepository.addEntries(id, listOf(entry("a"), entry("b"), entry("c")))
        fun current() = assertNotNull(PlaylistRepository.get(id))
        val entries = current().entries
        when (scenario) {
            "metadata", "write" -> {
                assertEquals("Initial", playlist.name)
                PlaylistRepository.rename(id, "  Saved  ")
                PlaylistRepository.rename(id, "  ")
                PlaylistRepository.setLoop(id, true)
                PlaylistRepository.setRemoveWatched(id, true)
                PlaylistRepository.setPreferLocalLibrary(id, false)
                assertEquals("Saved", current().name)
                assertTrue(current().loop && current().removeWatched)
                assertFalse(current().preferLocalLibrary)
                if (scenario == "write") {
                    PlaylistRepository.setResumeEntry(id, entries[1].entryId)
                    PlaylistRepository.moveEntry(id, 2, 0)
                    Files.writeString(root.resolve("playlist-id"), id)
                }
            }
            "add" -> {
                assertEquals(0, PlaylistRepository.addEntries(id, listOf(entry("a"), entry("b"))))
                assertEquals(2, PlaylistRepository.addEntries(id, listOf(entry("a", true), entry("a", true))))
                assertEquals(5, current().entries.size)
                assertEquals(5, current().entries.map { it.entryId }.distinct().size)
            }
            "reorder" -> {
                PlaylistRepository.setResumeEntry(id, entries[1].entryId)
                PlaylistRepository.moveEntry(id, 2, 0)
                assertEquals(listOf("c", "a", "b"), current().entries.map { it.videoId })
                assertEquals(entries[1].entryId, current().resumeEntry?.entryId)
                PlaylistRepository.moveEntry(id, -1, 0)
                assertEquals(listOf("c", "a", "b"), current().entries.map { it.videoId })
            }
            "remove" -> {
                PlaylistRepository.setResumeEntry(id, entries[1].entryId)
                PlaylistRepository.removeEntry(id, entries[0].entryId)
                assertEquals(entries[1].entryId, current().resumeEntry?.entryId)
                PlaylistRepository.removeEntry(id, entries[1].entryId)
                assertEquals(entries[2].entryId, current().resumeEntry?.entryId)
                PlaylistRepository.removeEntry(id, entries[2].entryId)
                assertTrue(current().entries.isEmpty())
                assertNull(current().resumeEntry)
            }
            "incomplete", "progress" -> {
                PlaylistPlaybackSession.start(id, entries[0].entryId)
                PlaylistPlaybackSession.playbackSpeed = 1.5f
                PlaylistPlaybackSession.reportProgress(if (scenario == "progress") 99_000 else 20_000, 100_000)
                PlaylistPlaybackSession.onPlayerExited()
                assertEquals(if (scenario == "progress") "b" else "a", current().resumeEntry?.videoId)
                assertNull(PlaylistPlaybackSession.current())
                assertNull(PlaylistPlaybackSession.playbackSpeed)
            }
            "finish" -> {
                PlaylistRepository.setRemoveWatched(id, true)
                PlaylistPlaybackSession.start(id, entries[0].entryId)
                assertEquals(entries[1], PlaylistPlaybackSession.upNext())
                assertEquals(entries[1], PlaylistPlaybackSession.finishCurrent())
                assertEquals(listOf("b", "c"), current().entries.map { it.videoId })
                assertEquals("b", current().resumeEntry?.videoId)
                PlaylistPlaybackSession.start(id, entries[1].entryId)
                assertEquals(entries[2], PlaylistPlaybackSession.finishCurrent())
                assertEquals(listOf("c"), current().entries.map { it.videoId })
            }
            "loop" -> {
                PlaylistPlaybackSession.start(id, entries.last().entryId)
                assertNull(PlaylistPlaybackSession.upNext())
                assertNull(PlaylistPlaybackSession.finishCurrent())
                PlaylistRepository.setLoop(id, true)
                assertEquals(entries.first(), PlaylistPlaybackSession.upNext())
                assertEquals(entries.first(), PlaylistPlaybackSession.finishCurrent())
                assertEquals(entries.first().entryId, current().resumeEntry?.entryId)
            }
            "random" -> {
                PlaylistRepository.addEntries(id, listOf(entry("channel", true)))
                PlaylistRepository.setRemoveWatched(id, true)
                val slot = current().entries.last()
                PlaylistPlaybackSession.start(id, slot.entryId)
                PlaylistPlaybackSession.finishCurrent()
                assertTrue(current().entries.any { it.entryId == slot.entryId })
            }
            "delete" -> {
                PlaylistPlaybackSession.start(id, entries[0].entryId)
                PlaylistRepository.delete(id)
                assertNull(PlaylistRepository.get(id))
                assertNull(PlaylistPlaybackSession.current())
            }
            else -> error("Unknown scenario $scenario")
        }
    }
}
