package com.nuvio.app.features.yamtrack

import com.nuvio.app.features.tracking.TrackingCatalogReference
import com.nuvio.app.features.tracking.TrackingEpisode
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YamtrackFloppyMirrorTest {
    private fun entry(mal: String, episode: Int?) = YamtrackAnimeResolution.Entry(
        mal = mal,
        ids = YamtrackScrobbleRepository.YamtrackIds(tmdb = "1"),
        title = null,
        episode = episode,
    )

    @Test
    fun `library membership only changes what differs from Floppy`() {
        assertEquals(FloppyMembershipChange.NONE, floppyMembershipChange(tracked = true, inLibrary = true))
        assertEquals(FloppyMembershipChange.NONE, floppyMembershipChange(tracked = false, inLibrary = false))
        assertEquals(FloppyMembershipChange.ADD, floppyMembershipChange(tracked = false, inLibrary = true))
        assertEquals(FloppyMembershipChange.REMOVE, floppyMembershipChange(tracked = true, inLibrary = false))
    }

    @Test
    fun `a mark sends each MAL entry's highest episode`() {
        val selected = selectAnimeEpisodePerEntry(
            listOf(entry("227", 3), entry("227", 6), entry("34034", 139), entry("227", 1)),
            watched = true,
        )
        assertEquals(mapOf("227" to 6, "34034" to 139), selected.associate { it.mal to it.episode })
    }

    @Test
    fun `an unmark sends each MAL entry's lowest episode`() {
        val selected = selectAnimeEpisodePerEntry(
            listOf(entry("227", 3), entry("227", 6), entry("34034", 139), entry("227", 2)),
            watched = false,
        )
        assertEquals(mapOf("227" to 2, "34034" to 139), selected.associate { it.mal to it.episode })
    }

    @Test
    fun `show-level anime entries are not sent as episodes`() {
        assertTrue(selectAnimeEpisodePerEntry(listOf(entry("227", null)), watched = true).isEmpty())
    }

    @Test
    fun `an episode inside the count is not scrobbled again`() {
        assertTrue(animeEpisodeAlreadyCounted(listOf(138), episode = 50))
        assertTrue(animeEpisodeAlreadyCounted(listOf(138), episode = 138))
        assertFalse(animeEpisodeAlreadyCounted(listOf(138), episode = 139))
        assertFalse(animeEpisodeAlreadyCounted(emptyList(), episode = 1))
    }

    @Test
    fun `unmark lowers the count to the episode before`() {
        assertEquals(AnimeUnmarkPlan.Lower(138), animeUnmarkPlan(listOf(139), episode = 139))
        // A count holds no gaps: every later episode goes with it.
        assertEquals(AnimeUnmarkPlan.Lower(49), animeUnmarkPlan(listOf(138), episode = 50))
    }

    @Test
    fun `unmarking the first episode untracks the entry`() {
        assertEquals(AnimeUnmarkPlan.Untrack, animeUnmarkPlan(listOf(6), episode = 1))
    }

    @Test
    fun `unmark leaves untracked entries and uncounted episodes alone`() {
        assertEquals(AnimeUnmarkPlan.NoChange, animeUnmarkPlan(emptyList(), episode = 3))
        assertEquals(AnimeUnmarkPlan.NoChange, animeUnmarkPlan(listOf(2), episode = 3))
    }

    @Test
    fun `only show-level series references are whole-show unmarks`() {
        fun reference(contentId: String, contentType: String, episode: Int?) = TrackingMediaReference(
            kind = TrackingMediaKind.SHOW,
            catalog = TrackingCatalogReference(contentId = contentId, contentType = contentType),
            episode = episode?.let { TrackingEpisode(season = 1, number = it) },
        )
        val ids = wholeShowUnmarkIds(
            listOf(
                reference("tt0279077", "series", episode = null),
                reference("tt0279077", "series", episode = 3),
                reference("tt1632701", "series", episode = 2),
                reference("tt0848228", "movie", episode = null),
            ),
        )
        assertEquals(setOf("tt0279077"), ids)
    }
}
