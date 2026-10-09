package com.nuvio.app.features.player

import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Linux targets for existing upstream identities, never an arbitrary DesktopEntry Exec string. */
internal sealed interface LinuxExternalPlayerTarget {
    val displayPath: String

    data class Executable(val path: String) : LinuxExternalPlayerTarget {
        override val displayPath: String get() = path
    }

    data class Flatpak(val launcher: String, val appId: String) : LinuxExternalPlayerTarget {
        override val displayPath: String get() = "$launcher [$appId]"
    }
}

/** Inject only discovery inputs; the shared platform still owns settings and player arguments. */
internal class LinuxExternalPlayerDiscovery(
    private val path: String = System.getenv("PATH").orEmpty(),
    private val standardDirectories: List<File> = listOf(File("/usr/local/bin"), File("/usr/bin"), File("/bin")),
    private val flatpakInstalled: (String, String) -> Boolean = ::isLinuxPlayerFlatpakInstalled,
) {
    // Match the upstream cache lifetime for Flatpak probes, but recheck executable permissions
    // and configured paths on every lookup so stale paths cannot look available.
    private val flatpakResults = mutableMapOf<Pair<String, String>, Boolean>()

    fun resolve(playerId: String, configuredPath: String? = null): LinuxExternalPlayerTarget? {
        val appId = flatpakIds[playerId] ?: return null
        if (!configuredPath.isNullOrBlank()) {
            return File(configuredPath).takeIf(::isLinuxPlayerExecutable)
                ?.let { LinuxExternalPlayerTarget.Executable(it.absolutePath) }
        }
        findExecutable(playerId)?.let { return LinuxExternalPlayerTarget.Executable(it) }
        val flatpak = findExecutable("flatpak") ?: return null
        val installed = synchronized(flatpakResults) {
            flatpakResults.getOrPut(flatpak to appId) { flatpakInstalled(flatpak, appId) }
        }
        return if (installed) LinuxExternalPlayerTarget.Flatpak(flatpak, appId) else null
    }

    fun findExecutable(name: String): String? =
        (path.split(File.pathSeparatorChar).asSequence()
            // Preserve whitespace in PATH directories. Do not search the current directory.
            .filter { it.isNotEmpty() && File(it).isAbsolute }
            .map(::File) + standardDirectories.asSequence())
            .map { File(it, name) }
            .firstOrNull(::isLinuxPlayerExecutable)
            ?.absolutePath

    companion object {
        private val flatpakIds = mapOf("mpv" to "io.mpv.Mpv", "vlc" to "org.videolan.VLC")
        fun supports(playerId: String): Boolean = playerId in flatpakIds
    }
}

internal fun isLinuxPlayerExecutable(file: File): Boolean = file.isFile && file.canExecute()

private fun isLinuxPlayerFlatpakInstalled(launcher: String, appId: String): Boolean = runCatching {
    val process = ProcessBuilder(launcher, "info", "--show-ref", appId)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    try {
        process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}.getOrDefault(false)

/** Convert POSIX files at the Linux boundary, leaving the shared Windows source policy intact. */
internal fun linuxExternalPlayerPolicySource(source: String): String? = runCatching {
    require('\u0000' !in source)
    if (source.startsWith('/') && !source.startsWith("//")) {
        File(source).toURI().toASCIIString()
    } else if (source.startsWith("file:", ignoreCase = true)) {
        val uri = URI(source)
        require(uri.rawAuthority.isNullOrEmpty() || uri.rawAuthority.equals("localhost", ignoreCase = true))
        require(uri.query == null && uri.fragment == null && uri.path.startsWith('/') && !uri.path.startsWith("//"))
        File(uri.path).toURI().toASCIIString()
    } else {
        source
    }
}.getOrNull()

internal fun linuxExternalPlayerSourceArgument(source: String): String =
    if (source.startsWith("file:", ignoreCase = true)) File(URI(source)).absolutePath else source

/** Local subtitle URIs need the same decoding as media, while addon URLs remain untouched. */
internal fun linuxExternalPlayerRequest(request: ExternalPlayerPlaybackRequest): ExternalPlayerPlaybackRequest? {
    val source = linuxExternalPlayerPolicySource(request.sourceUrl) ?: return null
    if (PlaybackSourcePolicy.check(source) != PlaybackSourcePolicy.Verdict.Allowed) return null
    val subtitles = request.subtitles?.map { subtitle ->
        val normalized = linuxExternalPlayerPolicySource(subtitle.url) ?: return null
        subtitle.copy(url = linuxExternalPlayerSourceArgument(normalized))
    }
    return request.copy(sourceUrl = source, subtitles = subtitles)
}

/** mpv's list append action takes a literal field, including commas and backslashes. */
internal fun linuxMpvHeaderArguments(headers: Map<String, String>): List<String> = buildList {
    val fields = headers.entries.filter { it.key.isNotBlank() && it.value.isNotBlank() }
    if (fields.isNotEmpty()) {
        // Preserve the upstream set operation's replacement of any configured header list.
        add("--http-header-fields-clr")
        fields.forEach { (key, value) -> add("--http-header-fields-append=$key: $value") }
    }
}

internal fun LinuxExternalPlayerTarget.commandPrefix(request: ExternalPlayerPlaybackRequest): List<String> = when (this) {
    is LinuxExternalPlayerTarget.Executable -> listOf(path)
    is LinuxExternalPlayerTarget.Flatpak -> buildList {
        add(launcher)
        add("run")
        // Mount only the requested local files read-only for this invocation. Flatpak has a
        // private /tmp and config directory, so manifest host access alone is insufficient for
        // Nuvio's cached subtitles. Escape Flatpak's own filesystem syntax, not shell syntax.
        (listOf(request.sourceUrl) + request.subtitles.orEmpty().map { it.url })
            .map(::linuxExternalPlayerSourceArgument)
            .map(::File)
            .filter { it.isAbsolute && it.isFile }
            .map { it.absolutePath }
            .distinct()
            .forEach { path ->
                val escaped = path.replace("\\", "\\\\").replace(":", "\\:")
                add("--filesystem=$escaped:ro")
            }
        add(appId)
    }
}

internal fun linuxSystemPlayerCommand(source: String, discovery: LinuxExternalPlayerDiscovery): List<String>? {
    val normalized = linuxExternalPlayerPolicySource(source) ?: return null
    if (!PlaybackSourcePolicy.allowsSystemHandler(normalized)) return null
    val opener = discovery.findExecutable("xdg-open") ?: return null
    return listOf(opener, linuxExternalPlayerSourceArgument(normalized))
}

/** Same detached stdio/working-directory contract as upstream, with bounded early failure detection. */
internal fun startLinuxExternalPlayerProcess(command: List<String>): Process {
    val process = ProcessBuilder(command)
        .apply { File(command.first()).parentFile?.takeIf(File::isDirectory)?.let(::directory) }
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    if (process.waitFor(200, TimeUnit.MILLISECONDS) && process.exitValue() != 0) {
        throw IOException("External process exited with code ${process.exitValue()}")
    }
    return process
}
