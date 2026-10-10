package com.nuvio.app.core.storage

import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LinuxStorageIsolationTest {
    @Test fun xdgRootStaysInsideFixture() = withHeadlessFixture { runHeadlessProbe(it, LinuxStorageIsolationProbe::class.java, "xdg") }
    @Test fun missingXdgUsesTemporaryHome() = withHeadlessFixture { runHeadlessProbe(it, LinuxStorageIsolationProbe::class.java, "fallback", configFallback = true) }
    @Test fun legacyMigrationCopiesIntoXdgWithoutTouchingOriginal() = withHeadlessFixture { runHeadlessProbe(it, LinuxStorageIsolationProbe::class.java, "migration") }
    @Test fun existingDestinationWinsOverLegacy() = withHeadlessFixture { runHeadlessProbe(it, LinuxStorageIsolationProbe::class.java, "existing") }
    @Test fun profileKeysPersistAcrossFreshProcessesAndCleanupPreservesOtherData() = withHeadlessFixture {
        runHeadlessProbe(it, LinuxStorageIsolationProbe::class.java, "writeProfiles")
        runHeadlessProbe(it, LinuxStorageIsolationProbe::class.java, "readAndClean")
        runHeadlessProbe(it, LinuxStorageIsolationProbe::class.java, "verifyClean")
    }
}

internal object LinuxStorageIsolationProbe {
    @JvmStatic fun main(args: Array<String>) {
        val root = Path.of(args[0])
        val scenario = args[1]
        assertEquals(root.resolve("home").toString(), System.getProperty("user.home"))
        assertEquals(root.resolve("home").toString(), System.getenv("HOME"))
        listOf("DISPLAY", "WAYLAND_DISPLAY", "NUVIO_RUN_LIVE_DISPLAY_TESTS", "DBUS_SESSION_BUS_ADDRESS").forEach { assertNull(System.getenv(it)) }
        val parent = if (scenario == "fallback") root.resolve("home/.config") else root.resolve("config")
        val destination = parent.resolve("nuviohtpc")
        val legacy = parent.resolve("nuvio")
        if (scenario in setOf("migration", "existing")) {
            Files.createDirectories(legacy.resolve("games"))
            Files.writeString(legacy.resolve("games/library.json"), "legacy-library")
            Files.writeString(legacy.resolve("marker"), "legacy")
            if (scenario == "existing") {
                Files.createDirectories(destination)
                Files.writeString(destination.resolve("marker"), "current")
            }
        }
        assertEquals(destination, DesktopStorage.rootDir)
        assertTrue(DesktopStorage.rootDir.startsWith(root))
        when (scenario) {
            "xdg", "fallback" -> {
                assertTrue(DesktopStorage.isFreshInstall)
                DesktopStorage.store("fixture").putString("key", "value")
                DesktopStorage.flushAll()
                assertTrue(Files.isRegularFile(destination.resolve("fixture.properties")))
                assertFalse(Files.exists(root.resolve("data/nuviohtpc")))
            }
            "migration", "existing" -> {
                assertFalse(DesktopStorage.isFreshInstall)
                assertEquals(if (scenario == "existing") "current" else "legacy", Files.readString(destination.resolve("marker")))
                assertEquals("legacy", Files.readString(legacy.resolve("marker")))
                assertEquals("legacy-library", Files.readString(legacy.resolve("games/library.json")))
                if (scenario == "migration") assertEquals("legacy-library", Files.readString(destination.resolve("games/library.json")))
                else assertFalse(Files.exists(destination.resolve("games/library.json")))
                assertFalse(Files.list(parent).use { paths -> paths.anyMatch { it.fileName.toString().contains(".migrating-") } })
            }
            "writeProfiles" -> {
                val store = DesktopStorage.store("nuvio_watch_progress")
                store.putString(ProfileScopedKey.of("progress", 1), "profile-one")
                store.putString(ProfileScopedKey.of("progress", 2), "profile-two")
                PlatformLocalAccountDataCleaner.accountStoreNames.forEach { DesktopStorage.store(it).putString("account", "old-account") }
                DesktopStorage.store("nuvio_player_settings").putString("volume", "27")
                Files.createDirectories(destination.resolve("games"))
                Files.writeString(destination.resolve("games/library.json"), "keep-library")
                Files.writeString(destination.resolve("unrelated.txt"), "keep-unrelated")
                DesktopStorage.flushAll()
            }
            "readAndClean" -> {
                val store = DesktopStorage.store("nuvio_watch_progress")
                assertEquals("profile-one", store.getString(ProfileScopedKey.of("progress", 1)))
                assertEquals("profile-two", store.getString(ProfileScopedKey.of("progress", 2)))
                assertNull(store.getString(ProfileScopedKey.of("progress", 3)))
                PlatformLocalAccountDataCleaner.accountStoreNames.forEach {
                    assertEquals("old-account", DesktopStorage.store(it).getString("account"))
                    Files.writeString(destination.resolve("$it.properties.bak"), "backup")
                    Files.writeString(destination.resolve("$it.properties.tmp"), "staging")
                }
                PlatformLocalAccountDataCleaner.wipe()
                assertNull(store.getString(ProfileScopedKey.of("progress", 1)), "Cached store must also be wiped")
                DesktopStorage.flushAll()
                assertPreserved(destination)
            }
            "verifyClean" -> {
                PlatformLocalAccountDataCleaner.accountStoreNames.forEach { name ->
                    assertNull(DesktopStorage.store(name).getString("account"))
                    listOf("", ".bak", ".tmp").forEach { assertFalse(Files.exists(destination.resolve("$name.properties$it"))) }
                }
                assertNull(DesktopStorage.store("nuvio_watch_progress").getString(ProfileScopedKey.of("progress", 2)))
                assertPreserved(destination)
            }
            else -> error("Unknown fixture scenario")
        }
    }
    private fun assertPreserved(destination: Path) {
        assertEquals("27", DesktopStorage.store("nuvio_player_settings").getString("volume"))
        assertEquals("keep-library", Files.readString(destination.resolve("games/library.json")))
        assertEquals("keep-unrelated", Files.readString(destination.resolve("unrelated.txt")))
    }
}
