package com.nuvio.app.features.simkl

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimklListCacheTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val keepWatching = { bucket: SimklListBucket, entry: SimklAllItemsEntry ->
        bucket != SimklListBucket.MOVIES && entry.hasStatus("watching")
    }

    private fun show(simkl: Int, status: String, lastWatched: String? = null) = SimklAllItemsEntry(
        status = status,
        lastWatched = lastWatched,
        show = SimklShowMedia("Show $simkl", ids = SimklMediaIds(simkl = simkl)),
    )

    private fun anime(simkl: Int, status: String) = SimklAllItemsEntry(
        status = status,
        anime = SimklShowMedia("Anime $simkl", ids = SimklMediaIds(simkl = simkl)),
    )

    private fun cache(vararg entries: SimklCachedEntry) =
        SimklListCache(dateFrom = "2026-10-01T00:00:00Z", entries = entries.toList())

    @Test
    fun `delta replaces a changed title and adds a new one`() {
        val before = cache(
            SimklCachedEntry(SimklListBucket.SHOWS, show(1, "watching", "S01E01")),
            SimklCachedEntry(SimklListBucket.SHOWS, show(2, "watching", "S03E04")),
        )
        val delta = SimklAllItemsResponse(shows = listOf(show(1, "watching", "S01E02"), show(3, "watching")))

        val after = before.applyDelta(delta, keepWatching)

        assertEquals(listOf(2, 1, 3), after.entries.map { it.entry.simklKey })
        assertEquals("S01E02", after.entries.single { it.entry.simklKey == 1 }.entry.lastWatched)
    }

    @Test
    fun `delta drops a title that left the bucket`() {
        val before = cache(
            SimklCachedEntry(SimklListBucket.SHOWS, show(1, "watching")),
            SimklCachedEntry(SimklListBucket.SHOWS, show(2, "watching")),
        )
        val delta = SimklAllItemsResponse(
            shows = listOf(show(1, "completed")),
            movies = listOf(SimklAllItemsEntry(status = "plantowatch", movie = SimklMovieMedia("M", ids = SimklMediaIds(simkl = 9)))),
        )

        val after = before.applyDelta(delta, keepWatching)

        assertEquals(listOf(2), after.entries.map { it.entry.simklKey })
    }

    @Test
    fun `delta follows a title SIMKL reclassified between shows and anime`() {
        val before = cache(SimklCachedEntry(SimklListBucket.SHOWS, show(5, "watching")))

        val after = before.applyDelta(SimklAllItemsResponse(anime = listOf(anime(5, "watching"))), keepWatching)

        assertEquals(SimklListBucket.ANIME, after.entries.single().bucket)
    }

    @Test
    fun `rewatch rows in a delta never become list entries`() {
        val before = cache(SimklCachedEntry(SimklListBucket.SHOWS, show(1, "watching", "S01E01")))
        val rewatchRow = show(1, "watching", "S09E09").copy(isRewatch = true, rewatchId = 7)

        val after = before.applyDelta(SimklAllItemsResponse(shows = listOf(rewatchRow)), keepWatching)

        assertEquals("S01E01", after.entries.single().entry.lastWatched)
    }

    @Test
    fun `deletion diff keeps only titles still in the library`() {
        val before = cache(
            SimklCachedEntry(SimklListBucket.SHOWS, show(1, "watching")),
            SimklCachedEntry(SimklListBucket.SHOWS, show(2, "watching")),
            SimklCachedEntry(SimklListBucket.SHOWS, SimklAllItemsEntry(status = "watching", show = SimklShowMedia("No id"))),
        )
        val library = json.decodeFromString<SimklAllItemsResponse>(
            """{"shows":[{"show":{"ids":{"simkl":1}}},{"show":{"ids":{"simkl":2}},"is_rewatch":true,"rewatch_id":4}]}""",
        )

        val after = before.withoutRemoved(SimklDeletionCheck.canonicalKeys(library))

        // Title 2 survives only as a rewatch row, which is not the canonical entry.
        assertEquals(listOf(1, null), after.entries.map { it.entry.simklKey })
    }

    @Test
    fun `cache survives a storage round trip`() {
        val before = cache(SimklCachedEntry(SimklListBucket.ANIME, anime(8, "watching")))

        val after = json.decodeFromString<SimklListCache>(json.encodeToString(before))

        assertEquals(before, after)
        assertTrue(after.hasBaseline)
    }

    @Test
    fun `plan to watch stamp moves when a title leaves for another status`() {
        val before = SimklActivities(movies = SimklCategoryActivity(planToWatch = "2026-10-01T00:00:00Z"))
        val after = SimklActivities(
            movies = SimklCategoryActivity(planToWatch = "2026-10-01T00:00:00Z", completed = "2026-10-03T08:00:00Z"),
        )

        assertNotEquals(simklPlanToWatchActivitiesStamp(before), simklPlanToWatchActivitiesStamp(after))
        assertNull(simklPlanToWatchActivitiesStamp(SimklActivities()))
    }

    @Test
    fun `playback stamp follows every category`() {
        val before = SimklActivities(tvShows = SimklCategoryActivity(playback = "2026-10-01T00:00:00Z"))
        val after = before.copy(anime = SimklCategoryActivity(playback = "2026-10-03T08:00:00Z"))

        assertNotEquals(simklPlaybackActivitiesStamp(before), simklPlaybackActivitiesStamp(after))
        assertNull(simklPlaybackActivitiesStamp(null))
    }
}
