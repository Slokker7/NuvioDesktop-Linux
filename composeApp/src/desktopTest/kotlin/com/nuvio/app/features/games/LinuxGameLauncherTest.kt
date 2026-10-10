package com.nuvio.app.features.games

import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class LinuxGameLauncherTest {
    private fun probe(case: String) = withHeadlessFixture { runHeadlessProbe(it, LinuxGameLauncherProbe::class.java, case) }
    @Test fun spacedExecutableAndLiteralArgumentsUseConfiguredDirectory() = probe("configured")
    @Test fun executableParentIsDefaultDirectory() = probe("default")
    @Test fun missingExecutableFailsWithoutExitCallback() = probe("missing")
    @Test fun nonExecutableFileFailsWithoutExitCallback() = probe("nonExecutable")
    @Test fun protocolUsesOnlyIsolatedFakeOpenerWithExactArgument() = probe("protocol")
}

internal object LinuxGameLauncherProbe {
    @JvmStatic fun main(args: Array<String>) {
        val root = Path.of(args[0])
        val scenario = args[1]
        val calls = AtomicInteger()
        val done = CountDownLatch(1)
        val exit = AtomicInteger(-1)
        val callback: (Int) -> Unit = { code -> exit.set(code); calls.incrementAndGet(); done.countDown() }
        val executable = root.resolve("Game with spaces")
        val sentinel = root.resolve("shell-must-not-run")
        if (scenario == "protocol") {
            val opener = root.resolve("bin/xdg-open")
            Files.writeString(opener, "#!/bin/sh\nprintf '%s\\n' \"\$#\" \"\$1\" > '${root.resolve("opener-args")}'\n")
            check(opener.toFile().setExecutable(true, true))
            assertEquals(root.resolve("bin").toString(), System.getenv("PATH"))
            val uri = "steam://run/123//literal%20space;\$(touch%20never)/"
            val result = GameLauncher.launch(GameEntry("p", title = "Protocol", executablePath = uri), callback)
            assertNull(result.getOrThrow())
            val recorded = root.resolve("opener-args")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((!Files.exists(recorded) || Files.readAllLines(recorded).size < 2) && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(listOf("1", uri), Files.readAllLines(recorded))
            assertEquals(0, calls.get())
            return
        }
        if (scenario != "missing") {
            Files.writeString(executable, "#!/bin/sh\nprintf '%s\\n' \"\$PWD\" \"\$#\" \"\$@\" > '${root.resolve("game-output")}'\nexit 37\n")
            check(executable.toFile().setExecutable(scenario != "nonExecutable", true))
        }
        val working = Files.createDirectories(root.resolve("working directory"))
        val literal = listOf("space value", "\$(touch $sentinel)", "; touch $sentinel", "\$HOME", "*", "a'\"b", "")
        val result = GameLauncher.launch(GameEntry("g", title = "Game", executablePath = executable.toString(), arguments = literal,
            workingDirectory = working.toString().takeIf { scenario == "configured" }), callback)
        if (scenario == "missing" || scenario == "nonExecutable") {
            assertTrue(result.isFailure)
            assertEquals(0, calls.get())
            assertFalse(Files.exists(root.resolve("game-output")))
        } else {
            assertNotNull(result.getOrThrow())
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(37, exit.get())
            assertEquals(1, calls.get())
            assertEquals(listOf((if (scenario == "configured") working else root).toString(), literal.size.toString()) + literal, Files.readAllLines(root.resolve("game-output")))
        }
        assertFalse(Files.exists(sentinel))
    }
}
