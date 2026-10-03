package com.nuvio.app.features.simkl

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object SimklSettingsStorage {
    private val store = DesktopStorage.store("nuvio_simkl_settings")

    actual fun loadPayload(): String? = store.getString(ProfileScopedKey.of("simkl_settings"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("simkl_settings"), payload)
    }
}

internal actual object SimklAuthStorage {
    private val store = DesktopStorage.store("nuvio_simkl_auth")
    private const val payloadKey = "simkl_auth_payload"

    actual fun loadPayload(): String? = store.getString(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of(payloadKey), payload)
    }

    actual fun clearPayload() {
        store.remove(ProfileScopedKey.of(payloadKey))
    }
}

internal actual object SimklRewatchStorage {
    private val store = DesktopStorage.store("nuvio_simkl_rewatches")
    private const val payloadKey = "simkl_rewatch_payload"

    actual fun loadPayload(): String? = store.getString(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of(payloadKey), payload)
    }

    actual fun clearPayload() {
        store.remove(ProfileScopedKey.of(payloadKey))
    }
}

internal actual object SimklWatchedSyncStorage {
    private val store = DesktopStorage.store("nuvio_simkl_watched_sync")
    private const val payloadKey = "simkl_watched_sync_payload"

    actual fun loadPayload(profileId: Int): String? = store.getString(ProfileScopedKey.of(payloadKey, profileId))

    actual fun savePayload(profileId: Int, payload: String?) {
        store.putString(ProfileScopedKey.of(payloadKey, profileId), payload)
    }
}

internal actual object SimklEpisodeCatalogStorage {
    private val store = DesktopStorage.store("nuvio_simkl_episode_catalog")
    private const val payloadKey = "simkl_episode_catalog_payload"

    actual fun loadPayload(): String? = store.getString(payloadKey)

    actual fun savePayload(payload: String?) {
        store.putString(payloadKey, payload)
    }
}

internal actual object SimklListCacheStorage {
    private val store = DesktopStorage.store("nuvio_simkl_list_cache")

    actual fun loadPayload(profileId: Int, key: String): String? = store.getString(ProfileScopedKey.of(key, profileId))

    actual fun savePayload(profileId: Int, key: String, payload: String?) {
        store.putString(ProfileScopedKey.of(key, profileId), payload)
    }
}
