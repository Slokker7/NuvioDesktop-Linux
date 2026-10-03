package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val BASE_URL = "https://api.simkl.com"

/** Which top-level array of an all-items response an entry came from. */
@Serializable
internal enum class SimklListBucket { SHOWS, MOVIES, ANIME }

@Serializable
internal data class SimklCachedEntry(
    val bucket: SimklListBucket,
    val entry: SimklAllItemsEntry,
)

/**
 * One status bucket of the user's SIMKL library (the watching list, Plan to Watch), kept across
 * launches so it can follow SIMKL's sync guide: one full read as the Phase 1 baseline, then
 * `date_from` deltas, with deletions found by an `extended=simkl_ids_only` diff only when
 * `removed_from_list` moves. Held in memory alone, the same list was re-downloaded in full on
 * every launch and after every history write.
 */
@Serializable
internal data class SimklListCache(
    /** The SIMKL user the entries belong to; a cache from another account is discarded. */
    val account: String? = null,
    /** Activities `all` read before the last successful read; the next delta's `date_from`. */
    val dateFrom: String? = null,
    /** [simklRemovedFromListStamp] as of the last deletion check or baseline. */
    val removedStamp: String? = null,
    val entries: List<SimklCachedEntry> = emptyList(),
) {
    val hasBaseline: Boolean get() = !dateFrom.isNullOrBlank()
}

/** SIMKL's own id for the entry's title, the key every delta and deletion diff matches on. */
internal val SimklAllItemsEntry.simklKey: Int?
    get() = (show ?: anime)?.ids?.simkl ?: movie?.ids?.simkl

internal fun SimklAllItemsResponse.cachedEntries(
    keep: (SimklListBucket, SimklAllItemsEntry) -> Boolean = { _, _ -> true },
): List<SimklCachedEntry> = buildList {
    shows.filter { keep(SimklListBucket.SHOWS, it) }.forEach { add(SimklCachedEntry(SimklListBucket.SHOWS, it)) }
    movies.filter { keep(SimklListBucket.MOVIES, it) }.forEach { add(SimklCachedEntry(SimklListBucket.MOVIES, it)) }
    anime.filter { keep(SimklListBucket.ANIME, it) }.forEach { add(SimklCachedEntry(SimklListBucket.ANIME, it)) }
}

/**
 * Applies a `date_from` delta, which carries every changed title in every status: each one replaces
 * its cached entry (matched by [simklKey] across buckets, since SIMKL can reclassify a title between
 * shows and anime), and is kept only if [keep] still wants it — a title that left the bucket comes
 * back in the delta under its new status and is dropped here. Rewatch rows are never list entries.
 */
internal fun SimklListCache.applyDelta(
    delta: SimklAllItemsResponse,
    keep: (SimklListBucket, SimklAllItemsEntry) -> Boolean,
): SimklListCache {
    val changed = delta.cachedEntries().filter { !it.entry.isRewatch && it.entry.simklKey != null }
    if (changed.isEmpty()) return this
    val changedKeys = changed.mapTo(mutableSetOf()) { it.entry.simklKey }
    val kept = entries.filterNot { it.entry.simklKey in changedKeys }
    return copy(entries = kept + changed.filter { keep(it.bucket, it.entry) })
}

/** Drops cached titles missing from [presentKeys]; an entry without a SIMKL id cannot be diffed and stays. */
internal fun SimklListCache.withoutRemoved(presentKeys: Set<Int>): SimklListCache =
    copy(entries = entries.filter { cached -> cached.entry.simklKey?.let { it in presentKeys } ?: true })

internal fun SimklAllItemsEntry.hasStatus(status: String): Boolean = this.status.equals(status, ignoreCase = true)

internal fun simklListCacheAccount(): String? =
    SimklAuthRepository.uiState.value.username?.trim()?.lowercase()?.takeIf(String::isNotBlank)

internal object SimklListCacheStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    fun load(profileId: Int, key: String): SimklListCache {
        val cache = SimklListCacheStorage.loadPayload(profileId, key)
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { json.decodeFromString<SimklListCache>(it) }.getOrNull() }
            ?: return SimklListCache()
        val account = simklListCacheAccount()
        return if (cache.account != null && account != null && cache.account != account) SimklListCache() else cache
    }

    fun save(profileId: Int, key: String, cache: SimklListCache) {
        SimklListCacheStorage.savePayload(
            profileId,
            key,
            json.encodeToString(cache.copy(account = cache.account ?: simklListCacheAccount())),
        )
    }

    fun clear(profileId: Int, key: String) = SimklListCacheStorage.savePayload(profileId, key, null)
}

/**
 * What the user currently holds on SIMKL, for the sync guide's deletion diff: one
 * `extended=simkl_ids_only` read of the whole library, the smallest response SIMKL has. Shared by
 * every list that needs the diff — Continue Watching, Plan to Watch and rewatch sessions all run it
 * when the same `removed_from_list` stamp moves, so the answer is remembered per stamp and the
 * library is read once per removal rather than once per caller. Rewatch rows are included whenever
 * rewatch tracking may send the flag at all, so the one read serves the rewatch check too.
 */
internal object SimklDeletionCheck {
    private val log = Logger.withTag("SimklDeletionCheck")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val mutex = Mutex()
    private var cachedFor: String? = null
    private var cached: SimklAllItemsResponse? = null

    suspend fun currentLibrary(removedStamp: String): SimklAllItemsResponse = mutex.withLock {
        val withRewatches = SimklRewatchRepository.requestsAllowed()
        val cacheKey = "${ProfileRepository.activeProfileId}|${simklListCacheAccount()}|$withRewatches|$removedStamp"
        cached?.takeIf { cachedFor == cacheKey }?.let { return@withLock it }
        val query = if (withRewatches) "allow_rewatch=yes&extended=simkl_ids_only" else "extended=simkl_ids_only"
        val response = simklRequest(
            method = "GET",
            url = SimklAuthRepository.appendParams("$BASE_URL/sync/all-items?$query"),
        )
        if (response.status !in 200..299) error("SIMKL deletion check failed (${response.status}).")
        val body = response.body.trim()
        val present = if (body.isEmpty() || body == "null" || body == "[]") {
            SimklAllItemsResponse()
        } else {
            json.decodeFromString<SimklAllItemsResponse>(body)
        }
        log.i { "SIMKL deletion check: ${present.shows.size} shows, ${present.movies.size} movies, ${present.anime.size} anime" }
        cachedFor = cacheKey
        cached = present
        present
    }

    /** SIMKL ids of the canonical (non-rewatch) titles in [library]. */
    fun canonicalKeys(library: SimklAllItemsResponse): Set<Int> =
        (library.shows + library.movies + library.anime)
            .filter { !it.isRewatch && it.rewatchId == null }
            .mapNotNullTo(mutableSetOf()) { it.simklKey }

    fun clear() {
        cachedFor = null
        cached = null
    }
}
