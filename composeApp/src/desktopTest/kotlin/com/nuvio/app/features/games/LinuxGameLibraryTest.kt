package com.nuvio.app.features.games

import com.nuvio.app.testing.withHeadlessFixture
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LinuxGameLibraryTest {
    @Test fun linuxPathsSurviveAddUpdateRemoveAndIndependentDirectoryWrites() = withHeadlessFixture { root ->
        runBlocking {
            val file = root.resolve("library.json")
            fun repository() = GameLibraryRepository(file, emptyList())
            val upper = GameEntry("upper", title = "Upper", executablePath = "/games/Été/My Game/Run", workingDirectory = "/games/Été/My Game", arguments = listOf("a b", "\$HOME;literal"))
            val lower = upper.copy(id = "lower", executablePath = "/games/Été/My Game/run")
            repository().saveGames(listOf(upper, lower))
            repository().saveLastExecutableDirectory("/games/Été/My Game")
            assertEquals(listOf(upper, lower), repository().load().games)
            val updated = upper.copy(title = "Updated", executablePath = "/opt/Other Game/start")
            repository().saveGames(listOf(updated, lower))
            assertEquals(listOf(updated, lower), repository().load().games)
            repository().saveGames(listOf(lower))
            assertEquals(listOf(lower), repository().load().games)
            assertEquals("/games/Été/My Game", repository().load().settings.lastExecutableDirectory)
            assertFalse(Files.exists(file.resolveSibling("library.json.tmp")))
        }
    }

    @Test fun linuxEntriesKeepPinnedShelvesAndFallBackWhenRowDisappears() = withHeadlessFixture { root ->
        runBlocking {
            val repo = GameLibraryRepository(root.resolve("library.json"), emptyList())
            val rows = ensureDefaultGameRows(listOf(GameRow("later", "Later")))
            val installed = GameEntry("one", title = "One", executablePath = "/games/start", rowId = "later")
            val tracked = GameEntry("two", title = "Two")
            repo.saveGames(listOf(installed, tracked))
            repo.saveRows(rows)
            val loaded = repo.load()
            assertEquals("later", loaded.games[0].effectiveRowId(loaded.rows))
            assertEquals(listOf("later", GameUninstalledRowId), gameShelfRows(loaded.games, loaded.rows).map { it.row.id })
            repo.saveRows(rows.filterNot { it.id == "later" })
            assertEquals(GameInstalledRowId, repo.load().games[0].effectiveRowId(repo.load().rows))
        }
    }

    @Test fun migrationCopiesLinuxPathsWithoutReplacingExistingDestination() = withHeadlessFixture { root ->
        runBlocking {
            val legacy = root.resolve("legacy.json")
            val target = root.resolve("games/library.json")
            val game = GameEntry("one", title = "One", executablePath = "/home/test/My Games/start")
            GameLibraryRepository(legacy, emptyList()).saveGames(listOf(game))
            val original = Files.readString(legacy)
            val migrated = GameLibraryRepository(target, listOf(legacy))
            assertEquals(listOf(game), migrated.load().games)
            assertEquals(original, Files.readString(legacy))
            migrated.saveGames(listOf(game.copy(title = "Changed")))
            assertEquals("Changed", GameLibraryRepository(target, listOf(legacy)).load().games.single().title)
            assertEquals(original, Files.readString(legacy))
        }
    }
}
