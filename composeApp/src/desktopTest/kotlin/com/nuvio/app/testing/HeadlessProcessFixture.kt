package com.nuvio.app.testing

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Child JVMs cannot inherit desktop services, protocol handlers or application data paths. */
internal fun withHeadlessFixture(test: (Path) -> Unit) {
    assumeTrue("Linux-only headless filesystem/process fixture", System.getProperty("os.name") == "Linux")
    val root = Files.createTempDirectory("nuvio-headless-batch-")
    try {
        listOf("home", "config", "data", "cache", "state", "runtime", "tmp", "bin").forEach {
            Files.createDirectories(root.resolve(it))
        }
        test(root)
    } finally {
        root.toFile().deleteRecursively()
    }
}

internal fun runHeadlessProbe(root: Path, main: Class<*>, scenario: String, configFallback: Boolean = false) {
    // Gradle's worker JVM puts test/runtime entries in its classloader, not necessarily java.class.path.
    val classpath = linkedSetOf<String>()
    classpath.addAll(System.getProperty("java.class.path").split(File.pathSeparator))
    var loader: ClassLoader? = main.classLoader
    while (loader != null) {
        if (loader is URLClassLoader) loader.urLs.filter { it.protocol == "file" }.forEach {
            classpath.add(File(it.toURI()).absolutePath)
        }
        loader = loader.parent
    }
    val output = root.resolve("probe-output.txt")
    val builder = ProcessBuilder(
        File(System.getProperty("java.home"), "bin/java").absolutePath,
        "-Djava.awt.headless=true", "-Duser.home=${root.resolve("home")}",
        "-Djava.io.tmpdir=${root.resolve("tmp")}", "-cp", classpath.joinToString(File.pathSeparator),
        main.name, root.toString(), scenario,
    ).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile())
    builder.environment().apply {
        clear()
        put("HOME", root.resolve("home").toString())
        if (!configFallback) put("XDG_CONFIG_HOME", root.resolve("config").toString())
        put("XDG_DATA_HOME", root.resolve("data").toString())
        put("XDG_CACHE_HOME", root.resolve("cache").toString())
        put("XDG_STATE_HOME", root.resolve("state").toString())
        put("XDG_RUNTIME_DIR", root.resolve("runtime").toString())
        put("TMPDIR", root.resolve("tmp").toString())
        put("PATH", root.resolve("bin").toString())
        put("LANG", "C.UTF-8")
    }
    val process = builder.start()
    try {
        assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Probe timed out: $scenario")
        assertEquals(0, process.exitValue(), Files.readString(output))
    } finally {
        process.descendants().forEach { it.destroyForcibly() }
        if (process.isAlive) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }
}
