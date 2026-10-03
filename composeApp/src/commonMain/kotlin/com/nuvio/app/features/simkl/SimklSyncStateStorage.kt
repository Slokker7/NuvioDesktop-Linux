package com.nuvio.app.features.simkl

/** Per-profile watched-history sync state; see [SimklWatchedRepository]. */
internal expect object SimklWatchedSyncStorage {
    fun loadPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String?)
}

/** Public episode listings, shared by every profile; see [SimklEpisodeCatalog]. */
internal expect object SimklEpisodeCatalogStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String?)
}

/** Per-profile list caches keyed by list; see [SimklListCache]. A null payload clears the entry. */
internal expect object SimklListCacheStorage {
    fun loadPayload(profileId: Int, key: String): String?
    fun savePayload(profileId: Int, key: String, payload: String?)
}
