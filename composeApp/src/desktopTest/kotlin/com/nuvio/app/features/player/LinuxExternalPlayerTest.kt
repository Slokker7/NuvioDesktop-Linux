package com.nuvio.app.features.player

import org.junit.Assume.assumeTrue
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.AudioFileFormat
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxExternalPlayerTest {
    private lateinit var directory: File

    @BeforeTest
    fun setup() {
        assumeTrue(System.getProperty("os.name").contains("linux", ignoreCase = true))
        directory = Files.createTempDirectory("nuvio-external-players-").toFile()
    }

    @AfterTest
    fun cleanup() {
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    private fun discovery(path: String = directory.path, standard: List<File> = emptyList()) =
        LinuxExternalPlayerDiscovery(path, standard)

    private fun executable(name: String, body: String = "exit 0\n"): File = File(directory, name).apply {
        parentFile.mkdirs()
        writeText("#!/bin/sh\n$body")
        assertTrue(setExecutable(true, true))
    }

    private fun recorder(name: String): Pair<File, File> {
        val output = File(directory, "argv-${name.hashCode()}")
        val exe = executable(name, "printf '%s\\0' \"\u0024@\" > '${output.path}'\n")
        return exe to output
    }

    private fun request(source: String = "https://example.test/video.mkv") = ExternalPlayerPlaybackRequest(
        sourceUrl = source,
        title = "Sýning - 日本語",
        resumePositionMs = 12_345,
        sourceHeaders = linkedMapOf(
            "User-Agent" to "Nuvio test",
            "Referer" to "https://example.test/",
            "X-Test" to "comma,back\\slash",
        ),
        subtitles = listOf(
            SubtitleInput(File(directory, "Íslenska subtitle.srt").path, "Íslenska", "is"),
            SubtitleInput("https://subs.example.test/second.vtt?token=x", "English", "en"),
        ),
    )

    private fun command(playerId: String, target: LinuxExternalPlayerTarget, request: ExternalPlayerPlaybackRequest) =
        assertNotNull(ExternalPlayerPlatform.linuxLaunchCommand(playerId, target, request))

    private fun runAndRead(command: List<String>, output: File): List<String> {
        val process = startLinuxExternalPlayerProcess(command)
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            return output.readText().split('\u0000').dropLast(1)
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `Linux settings expose only native upstream identities`() {
        assertEquals(listOf("mpv", "vlc", "system"), ExternalPlayerPlatform.availablePlayers().map { it.id })
    }

    @Test
    fun `PATH finds native names without exe suffix`() {
        val mpv = executable("mpv")
        val vlc = executable("vlc")
        assertEquals(LinuxExternalPlayerTarget.Executable(mpv.path), discovery().resolve("mpv"))
        assertEquals(LinuxExternalPlayerTarget.Executable(vlc.path), discovery().resolve("vlc"))
    }

    @Test
    fun `PATH preserves Unicode and whitespace and its search order`() {
        val first = executable(" first 日本語 /mpv")
        val second = executable("second/mpv")
        val resolver = discovery("${first.parent}:${second.parent}")
        assertEquals(LinuxExternalPlayerTarget.Executable(first.path), resolver.resolve("mpv"))
    }

    @Test
    fun `standard directories are a fallback after PATH`() {
        val standard = executable("standard/mpv")
        assertEquals(LinuxExternalPlayerTarget.Executable(standard.path), discovery("", listOf(standard.parentFile)).resolve("mpv"))
        val pathPlayer = executable("mpv")
        assertEquals(LinuxExternalPlayerTarget.Executable(pathPlayer.path), discovery(directory.path, listOf(standard.parentFile)).resolve("mpv"))
    }

    @Test
    fun `missing players and Windows names stay unavailable`() {
        executable("mpv.exe")
        executable("mpc-hc64.exe")
        assertNull(discovery().resolve("mpv"))
        assertNull(discovery().resolve("vlc"))
        assertNull(discovery().resolve("mpc-hc"))
    }

    @Test
    fun `non executable files and directories are skipped`() {
        File(directory, "mpv").writeText("not executable")
        File(directory, "vlc").mkdir()
        assertNull(discovery().resolve("mpv"))
        assertNull(discovery().resolve("vlc"))
    }

    @Test
    fun `configured AppImage has priority with arbitrary name spaces and Unicode`() {
        executable("mpv")
        val custom = executable("日本語 player/My mpv.AppImage")
        assertEquals(LinuxExternalPlayerTarget.Executable(custom.path), discovery().resolve("mpv", custom.path))
    }

    @Test
    fun `invalid configured executable does not silently substitute a discovered player`() {
        executable("mpv")
        val missing = File(directory, "missing").path
        assertNull(discovery().resolve("mpv", missing))
        val custom = executable("custom")
        assertTrue(custom.setExecutable(false, false))
        assertNull(discovery().resolve("mpv", custom.path))
    }

    @Test
    fun `removed executable and changed permissions are rechecked`() {
        val exe = executable("mpv")
        val resolver = discovery()
        assertNotNull(resolver.resolve("mpv"))
        assertTrue(exe.setExecutable(false, false))
        assertNull(resolver.resolve("mpv"))
        assertTrue(exe.setExecutable(true, true))
        assertNotNull(resolver.resolve("mpv"))
        assertTrue(exe.delete())
        assertNull(resolver.resolve("mpv"))
    }

    @Test
    fun `mpv argv preserves title resume all subtitles and literal arbitrary headers`() {
        val (exe, output) = recorder("mpv")
        val request = request()
        val args = runAndRead(command("mpv", assertNotNull(discovery().resolve("mpv")), request), output)
        assertEquals(
            listOf(
                "--force-media-title=${request.title}", "--start=12",
                "--http-header-fields-clr", "--http-header-fields-append=User-Agent: Nuvio test",
                "--http-header-fields-append=Referer: https://example.test/",
                "--http-header-fields-append=X-Test: comma,back\\slash",
                "--sub-file=${request.subtitles!![0].url}", "--sub-file=${request.subtitles[1].url}",
                "--", request.sourceUrl,
            ), args,
        )
        assertEquals(exe.path, discovery().resolve("mpv")?.displayPath)
    }

    @Test
    fun `VLC argv preserves resume first subtitle slaves and supported HTTP headers`() {
        val (_, output) = recorder("vlc")
        val request = request().copy(subtitles = request().subtitles!! + listOf(
            SubtitleInput("https://subs.example.test/extensionless", "Other", "en"),
            SubtitleInput("https://subs.example.test/third.srt#fragment", "Fragment", "en"),
        ))
        val args = runAndRead(command("vlc", assertNotNull(discovery().resolve("vlc")), request), output)
        assertEquals(listOf(
            "--meta-title=${request.title}", "--start-time=12", "--http-user-agent=Nuvio test",
            "--http-referrer=https://example.test/", "--sub-file=${request.subtitles!![0].url}",
            "--input-slave=https://subs.example.test/second.vtt?token=x", request.sourceUrl,
        ), args)
    }

    @Test
    fun `custom executable really receives HTTP handoff arguments`() {
        val (exe, output) = recorder("spaced 日本語/custom player.AppImage")
        val target = assertNotNull(discovery().resolve("mpv", exe.path))
        val args = runAndRead(command("mpv", target, request()), output)
        assertEquals("--start=12", args[1])
        assertEquals(request().sourceUrl, args.last())
        assertTrue(args.any { it.startsWith("--http-header-fields-append=") })
        assertEquals(2, args.count { it.startsWith("--sub-file=") })
    }

    @Test
    fun `POSIX media paths and file URIs decode to identical local argv`() {
        val (exe, output) = recorder("custom player")
        val media = File(directory, "日本語 media # 100%.mkv").apply { writeText("fixture") }
        val subtitle = File(directory, "subtitle # 日本語.srt").apply { writeText("fixture") }
        val target = assertNotNull(discovery().resolve("mpv", exe.path))
        for (source in listOf(media.path, media.toURI().toASCIIString())) {
            val request = request(source).copy(subtitles = listOf(SubtitleInput(subtitle.toURI().toASCIIString(), "Sub", "en")))
            val args = runAndRead(command("mpv", target, request), output)
            assertEquals(media.path, args.last())
            assertTrue("--sub-file=${subtitle.path}" in args)
        }
    }

    @Test
    fun `no shell interprets source title header subtitle or executable metacharacters`() {
        val marker = File(directory, "injected")
        val (exe, output) = recorder("custom ; 日本語 \u0024(player)")
        val payload = "\u0024(touch '${marker.path}'); & `touch '${marker.path}'`"
        val request = request("https://example.test/video;echo?x=1&y=2").copy(
            title = payload, sourceHeaders = mapOf("X-Test" to payload),
            subtitles = listOf(SubtitleInput("https://subs.example.test/$payload", "Sub", "en")),
        )
        val args = runAndRead(command("mpv", assertNotNull(discovery().resolve("mpv", exe.path)), request), output)
        assertTrue("--force-media-title=$payload" in args)
        assertEquals(request.sourceUrl, args.last())
        assertTrue(args.any { it.contains("X-Test: $payload") })
        assertTrue(args.any { it.contains(request.subtitles!![0].url) })
        assertFalse(marker.exists())
    }

    @Test
    fun `zero resume no headers and no subtitles add no optional switches`() {
        val args = command("mpv", LinuxExternalPlayerTarget.Executable("/unused"), ExternalPlayerPlaybackRequest(
            "https://example.test/video", "", resumePositionMs = 0,
        ))
        assertEquals(listOf("/unused", "--", "https://example.test/video"), args)
    }

    @Test
    fun `unsafe sources cannot reach either Linux command path`() {
        executable("xdg-open")
        val target = LinuxExternalPlayerTarget.Executable("/unused")
        for (source in listOf("--script=evil", "magnet:?xt=evil", "file://remote/share/video.mkv", "//remote/share/video.mkv", "file:////remote/share/video.mkv", "relative.mkv", "file:///tmp/video.mkv?query=bad", "file:///tmp/video.mkv#fragment")) {
            assertNull(ExternalPlayerPlatform.linuxLaunchCommand("mpv", target, request(source)), source)
            assertNull(linuxSystemPlayerCommand(source, discovery()), source)
        }
    }

    @Test
    fun `localhost file URI becomes an absolute path without treating plus as space`() {
        val normalized = assertNotNull(linuxExternalPlayerPolicySource("file://localhost/tmp/a+b%20日本語.mkv"))
        assertEquals("/tmp/a+b 日本語.mkv", linuxExternalPlayerSourceArgument(normalized))
    }

    @Test
    fun `xdg-open fallback executes with one untouched HTTP argument`() {
        val (_, output) = recorder("xdg-open")
        val source = "https://example.test/video;echo?token=a%20b&x=日本語"
        assertEquals(listOf(source), runAndRead(assertNotNull(linuxSystemPlayerCommand(source, discovery())), output))
    }

    @Test
    fun `xdg-open local argv decodes spaces Unicode hashes and encoded media extension`() {
        val (_, output) = recorder("xdg-open")
        val media = File(directory, "日本語 movie # 100%.mkv")
        for (source in listOf(media.path, media.toURI().toASCIIString(), media.toURI().toASCIIString().removeSuffix("mkv") + "%6d%6b%76")) {
            assertEquals(listOf(media.path), runAndRead(assertNotNull(linuxSystemPlayerCommand(source, discovery())), output))
        }
    }

    @Test
    fun `system handler missing or non media sources stay unavailable`() {
        assertNull(linuxSystemPlayerCommand("https://example.test/video", discovery()))
        executable("xdg-open")
        assertNull(linuxSystemPlayerCommand(File(directory, "application.desktop").path, discovery()))
        assertNull(linuxSystemPlayerCommand("rtsp://example.test/video", discovery()))
    }

    @Test
    fun `Flatpak uses known identity and real bounded info probe`() {
        executable("flatpak", "[ \"\u00241\" = info ] && [ \"\u00243\" = org.videolan.VLC ]\n")
        assertNull(discovery().resolve("mpv"))
        assertEquals(LinuxExternalPlayerTarget.Flatpak(File(directory, "flatpak").path, "org.videolan.VLC"), discovery().resolve("vlc"))
    }

    @Test
    fun `native executable takes priority over Flatpak without probing`() {
        executable("mpv")
        executable("flatpak")
        val resolver = LinuxExternalPlayerDiscovery(directory.path, emptyList()) { _, _ -> error("must not probe") }
        assertTrue(resolver.resolve("mpv") is LinuxExternalPlayerTarget.Executable)
    }

    @Test
    fun `Flatpak argv keeps app identity separate from player options and local file grants`() {
        val (exe, output) = recorder("flatpak")
        val media = File(directory, "media:ro 日本語.mkv").apply { writeText("fixture") }
        val subtitle = File(directory, "sub\\title:srt.srt").apply { writeText("fixture") }
        val request = request(media.path).copy(subtitles = listOf(SubtitleInput(subtitle.path, "Sub", "en")))
        for ((player, appId) in listOf("mpv" to "io.mpv.Mpv", "vlc" to "org.videolan.VLC")) {
            val target = LinuxExternalPlayerTarget.Flatpak(exe.path, appId)
            val args = runAndRead(command(player, target, request), output)
            assertEquals(listOf("run", "--filesystem=${media.path.replace(":", "\\:")}:ro",
                "--filesystem=${subtitle.path.replace("\\", "\\\\").replace(":", "\\:")}:ro", appId), args.take(4))
            assertEquals(media.path, args.last())
            assertTrue("--sub-file=${subtitle.path}" in args)
        }
    }

    @Test
    fun `Flatpak HTTP request does not add filesystem grants`() {
        val target = LinuxExternalPlayerTarget.Flatpak("/flatpak", "io.mpv.Mpv")
        val args = command("mpv", target, request().copy(subtitles = emptyList()))
        assertEquals(listOf("/flatpak", "run", "io.mpv.Mpv"), args.take(3))
        assertFalse(args.any { it.startsWith("--filesystem=") })
    }

    @Test
    fun `child start and immediate nonzero failures are reported`() {
        assertFailsWith<IOException> { startLinuxExternalPlayerProcess(listOf(File(directory, "missing").path)) }
        val denied = executable("denied")
        assertTrue(denied.setExecutable(false, false))
        assertFailsWith<IOException> { startLinuxExternalPlayerProcess(listOf(denied.path)) }
        val failed = executable("failed", "exit 23\n")
        assertFailsWith<IOException> { startLinuxExternalPlayerProcess(listOf(failed.path)) }
    }

    @Test
    fun `unsupported saved identities return unavailable rather than changing settings`() {
        for (id in listOf("mpc-hc", "mpc-be", "potplayer", "energy")) {
            assertEquals(ExternalPlayerOpenResult.NoPlayerAvailable, ExternalPlayerPlatform.open(request(), id))
        }
        assertEquals(ExternalPlayerOpenResult.Failed, ExternalPlayerPlatform.open(request("--script=evil"), "mpv"))
    }

    @Test
    fun `optional installed mpv really plays local and HTTP media silently without a window`() {
        // Explicit opt-in prevents an ordinary test run from depending on an installed player.
        val mpv = System.getenv("NUVIO_EXTERNAL_PLAYER_MPV_SMOKE")
        assumeTrue(!mpv.isNullOrBlank())
        val target = LinuxExternalPlayerTarget.Executable(assertNotNull(mpv))
        val media = File(directory, "日本語 generated audio.wav")
        AudioInputStream(ByteArrayInputStream(ByteArray(32_000)), AudioFormat(8_000f, 16, 1, true, false), 16_000).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, media)
        }
        val subtitle = File(directory, "Íslenska subtitle.srt").apply {
            writeText("1\n00:00:00,000 --> 00:00:02,000\nSilent test\n")
        }
        val headers = ConcurrentHashMap<String, String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media.wav") { exchange ->
            listOf("User-Agent", "Referer", "X-Test").forEach { key ->
                exchange.requestHeaders.getFirst(key)?.let { headers[key] = it }
            }
            exchange.responseHeaders.add("Content-Type", "audio/wav")
            exchange.sendResponseHeaders(200, media.length())
            exchange.responseBody.use { it.write(media.readBytes()) }
            exchange.close()
        }
        server.start()
        try {
            val sources = listOf(media.path, "http://127.0.0.1:${server.address.port}/media.wav")
            sources.forEachIndexed { index, source ->
                val request = request(source).copy(resumePositionMs = 1_000, subtitles = listOf(SubtitleInput(subtitle.path, "Sub", "is")))
                val log = File(directory, "mpv-$index.log")
                val mapped = command("mpv", target, request)
                val bounded = mapped.take(1) + listOf(
                    "--no-config", "--vo=null", "--ao=null", "--force-window=no", "--idle=no",
                    "--keep-open=no", "--input-terminal=no", "--input-media-keys=no", "--length=0.2", "--log-file=${log.path}",
                ) + mapped.drop(1)
                val process = startLinuxExternalPlayerProcess(bounded)
                try {
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                    assertEquals(0, process.exitValue(), log.takeIf(File::exists)?.readText())
                    assertTrue(log.readText().contains("AO: [null]"), "mpv must initialize playback")
                    assertTrue(log.readText().contains("--start=1"), "mpv must receive the mapped resume")
                    assertTrue(log.readText().contains("srt"), "mpv must load the generated subtitle")
                } finally {
                    if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                }
            }
            assertEquals("Nuvio test", headers["User-Agent"])
            assertEquals("https://example.test/", headers["Referer"])
            assertEquals("comma,back\\slash", headers["X-Test"])
        } finally {
            server.stop(0)
        }
    }
}
