package com.nuvio.app.features.yamtrack

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingLibraryProvider
import com.nuvio.app.features.tracking.TrackingLibrarySnapshot
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import com.nuvio.app.features.tracking.TrackingMembershipResolution
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.trakt.parseTraktIsoDateTimeToEpochMs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object YamtrackLibraryAdapter : TrackingLibraryProvider {
    override val providerId: TrackingProviderId = TrackingProviderId.YAMTRACK
    private const val TAB_KEY = "floppy:library"
    private const val PAGE_SIZE = 500
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val changes: Flow<Unit> = changed
    override val connectionRefreshIntent = TrackingRefreshIntent.AUTOMATIC

    private var state = TrackingLibrarySnapshot()

    override fun ensureLoaded() = YamtrackSettingsRepository.ensureLoaded()
    override fun clearLocalState() { state = TrackingLibrarySnapshot() }
    override fun onProfileChanged() { clearLocalState() }

    override suspend fun refresh(intent: TrackingRefreshIntent) {
        val credentials = YamtrackSettingsRepository.activeCredentials()
        if (credentials == null) {
            state = TrackingLibrarySnapshot(hasLoaded = true)
            changed.tryEmit(Unit)
            return
        }
        state = state.copy(isLoading = true, errorMessage = null)
        changed.tryEmit(Unit)
        val (baseUrl, token) = credentials
        val collected = mutableListOf<LibraryItem>()
        var offset = 0
        while (true) {
            val response = httpRequestRaw(
                "GET",
                "$baseUrl/api/v1/media/?limit=$PAGE_SIZE&offset=$offset",
                floppyLibraryHeaders(token),
                "",
            )
            if (response.status !in 200..299) {
                state = state.copy(
                    isLoading = false,
                    hasLoaded = true,
                    errorMessage = "Floppy library returned HTTP ${response.status}",
                )
                changed.tryEmit(Unit)
                return
            }
            val page = json.decodeFromString<FloppyLibraryPage>(response.body)
            collected += page.results.mapNotNull(FloppyTrackedMedia::toLibraryItem)
            if (page.pagination.next.isNullOrBlank() || page.results.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        val sections = collected.groupBy(LibraryItem::type).map { (type, items) ->
            LibrarySection(
                type = type,
                displayTitle = if (type == "movie") "Movies" else "TV Shows",
                items = items.sortedByDescending(LibraryItem::savedAtEpochMs),
            )
        }.sortedBy(LibrarySection::displayTitle)
        state = TrackingLibrarySnapshot(
            items = collected,
            sections = sections,
            tabs = listOf(
                TrackingLibraryTab(
                    key = TAB_KEY,
                    title = "Floppy Library",
                    providerId = providerId,
                    kind = TrackingLibraryTabKind.WATCHLIST,
                ),
            ),
            hasLoaded = true,
        )
        changed.tryEmit(Unit)
    }

    override fun snapshot(): TrackingLibrarySnapshot = state

    override fun contains(contentId: String, contentType: String?): Boolean =
        state.items.any { it.id == contentId && (contentType == null || it.type == contentType) }

    override fun find(contentId: String): LibraryItem? = state.items.firstOrNull { it.id == contentId }

    override suspend fun membership(item: LibraryItem): Map<String, Boolean> {
        // Anime lives on a MAL row whose id never matches the catalog id being browsed, so the
        // snapshot cannot answer for it — ask Floppy directly.
        val credentials = YamtrackSettingsRepository.activeCredentials()
        val anime = credentials?.let { item.resolveFloppyAnime() }
        if (credentials != null && anime is YamtrackAnimeResolution.Entry) {
            val (baseUrl, token) = credentials
            return mapOf(TAB_KEY to isTracked(animeMediaUrl(baseUrl, anime.mal), floppyLibraryHeaders(token)))
        }
        return mapOf(TAB_KEY to contains(item.id, item.type))
    }

    override fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean> =
        mapOf(TAB_KEY to (currentMembership[TAB_KEY] != true))

    override suspend fun applyMembership(
        profileId: Int,
        item: LibraryItem,
        desiredMembership: Map<String, Boolean>,
        destructiveRemovalConfirmed: Boolean,
    ): TrackingMembershipResolution? {
        if (profileId != ProfileRepository.activeProfileId) return null
        val desired = desiredMembership[TAB_KEY] ?: return null
        // Anime goes to its MAL row, the same entry watched-history writes use. Anime that cannot
        // be pinned to one keeps the TV route below, as before.
        YamtrackSettingsRepository.activeCredentials()?.let { (baseUrl, token) ->
            val anime = item.resolveFloppyAnime()
            if (anime is YamtrackAnimeResolution.Entry) {
                applyAnimeMembership(baseUrl, floppyLibraryHeaders(token), anime.mal, desired)
                refresh(TrackingRefreshIntent.INVALIDATED)
                return TrackingMembershipResolution(providerId, TAB_KEY, TAB_KEY)
            }
        }
        val current = contains(item.id, item.type)
        if (desired == current) return TrackingMembershipResolution(providerId, TAB_KEY, TAB_KEY)
        val (baseUrl, token) = YamtrackSettingsRepository.activeCredentials()
            ?: error("Floppy is not connected")
        val target = item.toFloppyLibraryTarget() ?: error("Floppy could not resolve this title")
        val response = if (desired) {
            httpRequestRaw(
                "POST",
                "$baseUrl/api/v1/media/${target.mediaType}/",
                floppyLibraryHeaders(token),
                json.encodeToString(FloppyTrackRequest(target.source, target.id)),
            )
        } else {
            httpRequestRaw(
                "DELETE",
                "$baseUrl/api/v1/media/${target.mediaType}/${target.source}/${target.id}/",
                floppyLibraryHeaders(token),
                "",
            )
        }
        if (response.status !in 200..299 && !(response.status == 404 && !desired)) {
            error("Floppy library update failed (${response.status}): ${response.body.take(200)}")
        }
        refresh(TrackingRefreshIntent.INVALIDATED)
        return TrackingMembershipResolution(providerId, TAB_KEY, TAB_KEY)
    }

    /**
     * Mirrors a library add/remove made against another Library source (e.g. SIMKL) onto Floppy.
     *
     * Adding tracks the title as Planning, but only when Floppy is not already tracking it: the
     * collection POST always appends a new entry, so a watched title would otherwise gain a second,
     * Planning one. Removing deletes the tracked item with all its entries, watched ones included —
     * the same outcome as SIMKL's own library removal.
     *
     * Ids resolve the way watched-history writes do (tmdb first), so a library entry and a later
     * mark-watched land on the same Floppy item rather than an imdb/tmdb pair of duplicates.
     */
    suspend fun mirrorMembership(item: LibraryItem, inLibrary: Boolean) {
        val (baseUrl, token) = YamtrackSettingsRepository.activeCredentials() ?: return
        val headers = floppyLibraryHeaders(token)
        when (val anime = item.resolveFloppyAnime()) {
            is YamtrackAnimeResolution.Entry -> return applyAnimeMembership(baseUrl, headers, anime.mal, inLibrary)
            // Anime without a MAL entry is skipped: the TV route would file it as a duplicate
            // TMDB show beside the Anime row that history writes use.
            is YamtrackAnimeResolution.Unaddressable -> error("Floppy skipped anime ${item.name}: ${anime.reason}")
            YamtrackAnimeResolution.NotAnime -> Unit
        }
        val target = item.resolveFloppyLibraryTarget() ?: error("Floppy could not resolve ${item.name}")
        val mediaUrl = "$baseUrl/api/v1/media/${target.mediaType}/${target.source}/${target.id}/"
        val response = when (floppyMembershipChange(isTracked(mediaUrl, headers), inLibrary)) {
            FloppyMembershipChange.NONE -> return
            FloppyMembershipChange.ADD -> httpRequestRaw(
                "POST",
                "$baseUrl/api/v1/media/${target.mediaType}/",
                headers,
                json.encodeToString(FloppyPlanRequest(target.source, target.id, status = 0)),
            )
            FloppyMembershipChange.REMOVE -> httpRequestRaw("DELETE", mediaUrl, headers, "")
        }
        if (response.status !in 200..299 && !(response.status == 404 && !inLibrary)) {
            error("Floppy library mirror failed (${response.status}): ${response.body.take(200)}")
        }
    }

    /** Anime lives on Floppy as a MAL row; adding needs an explicit starting `progress`. */
    private suspend fun applyAnimeMembership(
        baseUrl: String,
        headers: Map<String, String>,
        mal: String,
        inLibrary: Boolean,
    ) {
        val mediaUrl = animeMediaUrl(baseUrl, mal)
        val response = when (floppyMembershipChange(isTracked(mediaUrl, headers), inLibrary)) {
            FloppyMembershipChange.NONE -> return
            FloppyMembershipChange.ADD -> httpRequestRaw(
                "POST",
                "$baseUrl/api/v1/media/anime/",
                headers,
                json.encodeToString(FloppyAnimePlanRequest("mal", mal, status = 0, progress = 0)),
            )
            FloppyMembershipChange.REMOVE -> httpRequestRaw("DELETE", mediaUrl, headers, "")
        }
        if (response.status !in 200..299 && !(response.status == 404 && !inLibrary)) {
            error("Floppy anime library update failed (${response.status}): ${response.body.take(200)}")
        }
    }

    private suspend fun isTracked(mediaUrl: String, headers: Map<String, String>): Boolean {
        val existing = httpRequestRaw("GET", mediaUrl, headers, "")
        return existing.status in 200..299 && json.decodeFromString<FloppyTrackedFlag>(existing.body).tracked
    }

    private fun animeMediaUrl(baseUrl: String, mal: String) = "$baseUrl/api/v1/media/anime/mal/$mal/"

    private suspend fun LibraryItem.resolveFloppyAnime(): YamtrackAnimeResolution =
        YamtrackScrobbleRepository.resolveAnimeEntry(
            contentType = type,
            parentMetaId = id,
            videoId = null,
            title = name,
            seasonNumber = null,
            episodeNumber = null,
            isAnime = type.equals("anime", ignoreCase = true),
        )
}

internal enum class FloppyMembershipChange { NONE, ADD, REMOVE }

/**
 * What a library add/remove must do on Floppy given whether it already tracks the title.
 *
 * Adding a tracked title is a no-op rather than a POST: the collection POST always appends a new
 * entry, so a watched title would gain a second, Planning one.
 */
internal fun floppyMembershipChange(tracked: Boolean, inLibrary: Boolean): FloppyMembershipChange = when {
    tracked == inLibrary -> FloppyMembershipChange.NONE
    inLibrary -> FloppyMembershipChange.ADD
    else -> FloppyMembershipChange.REMOVE
}

private suspend fun LibraryItem.resolveFloppyLibraryTarget(): FloppyLibraryTarget? {
    val mediaType = if (type.equals("movie", true)) "movie" else "tv"
    val resolved = YamtrackScrobbleRepository.buildItem(
        contentType = type,
        parentMetaId = id,
        videoId = null,
        title = name,
        episodeTitle = null,
        seasonNumber = null,
        episodeNumber = null,
        isAnime = type.equals("anime", true),
    )?.ids
    resolved?.tmdb?.takeIf(String::isNotBlank)?.let { return FloppyLibraryTarget(mediaType, "tmdb", it) }
    resolved?.imdb?.takeIf(String::isNotBlank)?.let { return FloppyLibraryTarget(mediaType, "imdb", it) }
    resolved?.tvdb?.takeIf(String::isNotBlank)?.let { return FloppyLibraryTarget(mediaType, "tvdb", it) }
    return toFloppyLibraryTarget()
}

private data class FloppyLibraryTarget(val mediaType: String, val source: String, val id: String)

private fun LibraryItem.toFloppyLibraryTarget(): FloppyLibraryTarget? {
    val mediaType = if (type.equals("movie", true)) "movie" else "tv"
    tmdbId?.let { return FloppyLibraryTarget(mediaType, "tmdb", it.toString()) }
    imdbId?.takeIf(String::isNotBlank)?.let { return FloppyLibraryTarget(mediaType, "imdb", it) }
    if (id.startsWith("tmdb:", true)) return FloppyLibraryTarget(mediaType, "tmdb", id.substringAfter(':'))
    if (id.startsWith("tt", true)) return FloppyLibraryTarget(mediaType, "imdb", id)
    if (id.startsWith("tvdb:", true)) return FloppyLibraryTarget(mediaType, "tvdb", id.substringAfter(':'))
    return null
}

@Serializable private data class FloppyTrackRequest(val source: String, @SerialName("media_id") val mediaId: String)
/** Status 0 is Planning; stated explicitly rather than leaning on the endpoint's default. */
@Serializable private data class FloppyPlanRequest(
    val source: String,
    @SerialName("media_id") val mediaId: String,
    // No default: this Json omits defaulted fields, which would drop the status from the body.
    val status: Int,
)
@Serializable private data class FloppyAnimePlanRequest(
    val source: String,
    @SerialName("media_id") val mediaId: String,
    val status: Int,
    val progress: Int,
)
@Serializable private data class FloppyTrackedFlag(val tracked: Boolean = false)
@Serializable private data class FloppyLibraryPage(
    val pagination: FloppyLibraryPagination = FloppyLibraryPagination(),
    val results: List<FloppyTrackedMedia> = emptyList(),
)
@Serializable private data class FloppyLibraryPagination(val next: String? = null)
@Serializable private data class FloppyTrackedMedia(
    val item: FloppyLibraryItem? = null,
    @SerialName("created_at") val createdAt: String? = null,
)
@Serializable private data class FloppyLibraryItem(
    @SerialName("media_id") val mediaId: String? = null,
    val source: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val image: String? = null,
)

private fun FloppyTrackedMedia.toLibraryItem(): LibraryItem? {
    val item = item ?: return null
    val rawId = item.mediaId?.takeIf(String::isNotBlank) ?: return null
    val type = when (item.mediaType) {
        "movie" -> "movie"
        "tv", "anime" -> "series"
        else -> return null
    }
    val id = when (item.source?.lowercase()) {
        "imdb" -> rawId
        "tmdb" -> "tmdb:$rawId"
        "tvdb" -> "tvdb:$rawId"
        // Anime library adds land on MAL rows; without this they were tracked but never listed.
        "mal" -> "mal:$rawId"
        else -> return null
    }
    return LibraryItem(
        id = id,
        type = type,
        name = item.title ?: id,
        poster = item.image,
        imdbId = id.takeIf { it.startsWith("tt") },
        tmdbId = if (id.startsWith("tmdb:")) id.substringAfter(':').toIntOrNull() else null,
        malId = if (id.startsWith("mal:")) id.substringAfter(':').toIntOrNull() else null,
        savedAtEpochMs = createdAt?.let(::parseTraktIsoDateTimeToEpochMs) ?: 0L,
    )
}

private fun floppyLibraryHeaders(token: String) = mapOf(
    "Accept" to "application/json",
    "Content-Type" to "application/json",
    "Authorization" to "Bearer $token",
)
