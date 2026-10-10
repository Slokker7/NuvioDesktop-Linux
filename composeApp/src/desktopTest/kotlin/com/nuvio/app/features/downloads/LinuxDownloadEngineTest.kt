package com.nuvio.app.features.downloads

import com.nuvio.app.testing.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class LinuxDownloadEngineTest {
    private fun probe(case: String) = withHeadlessFixture { runHeadlessProbe(it, LinuxDownloadEngineProbe::class.java, case) }
    @Test fun normalTransferPreservesHeadersBytesAndCallbackContract() = probe("normal")
    @Test fun localhostRedirectCompletesNestedDestination() = probe("redirect")
    @Test fun resumeAppendsRangeToExistingPartial() = probe("resume")
    @Test fun ignoredRangeRestartsWithoutDuplicatingPrefix() = probe("ignoreRange")
    @Test fun rangeNotSatisfiableRestartsFromZero() = probe("range416")
    @Test fun retryableHttpFailureRetriesThenSucceeds() = probe("retry")
    @Test fun truncatedTransferResumesOnRetry() = probe("truncated")
    @Test fun cancellationRetainsPartialAndDoesNotComplete() = probe("cancel")
    @Test fun invalidSignatureIsRejectedAndPartialRemoved() = probe("signature")
    @Test fun unsafeContentTypeIsRejectedAndPartialRemoved() = probe("contentType")
    @Test fun configuredSizeLimitRejectsBeforeFinalization() = probe("sizeLimit")
    @Test fun clientErrorHasOneNonTransientFailureCallback() = probe("clientError")
    @Test fun payloadAbove16MiBUsesFourSegmentsAndCleansUp() = probe("segments")
    @Test fun rejectedSegmentRangesFallBackToSequentialTransfer() = probe("segmentFallback")
}

internal object LinuxDownloadEngineProbe {
    private data class Result(val path: String? = null, val total: Long? = null, val error: String? = null, val transient: Boolean = false)

    // Only used to await quiescence before checking files/callback counts or deleting the fixture.
    // The public task contract intentionally exposes cancel only; behavior assertions use its API.
    private fun awaitStopped(handle: DownloadsTaskHandle) {
        val field = handle.javaClass.getDeclaredField("job").apply { isAccessible = true }
        // The supervisor remains active after its download child finishes; await the children,
        // not the supervisor itself. Finally cancels the supervisor as well.
        runBlocking { withTimeout(20_000) { (field.get(handle) as Job).children.forEach { it.join() } } }
    }

    @JvmStatic fun main(args: Array<String>) {
        val root = Path.of(args[0])
        val scenario = args[1]
        val segmented = scenario in setOf("segments", "segmentFallback")
        val media = ByteArray(if (segmented) 16 * 1024 * 1024 + 65_536 else 65_536) { (it * 31 + 7).toByte() }
        "ftyp".toByteArray().copyInto(media, 4)
        val directory = Files.createDirectories(root.resolve("downloads"))
        val name = if (scenario == "redirect") "Show/Season 01/fixture.mp4" else "fixture.mp4"
        val destination = directory.resolve(name)
        val partial = directory.resolve("$name.part")
        val seeded = scenario in setOf("resume", "ignoreRange", "range416", "cancel", "contentType", "sizeLimit")
        if (seeded) Files.write(partial, media.copyOfRange(0, 4096))
        val calls = AtomicInteger()
        val progress = CopyOnWriteArrayList<Pair<Long, Long?>>()
        val success = AtomicInteger()
        val failure = AtomicInteger()
        val completed = CompletableFuture<Result>()
        val progressEntered = CountDownLatch(1)
        val cancelReleased = CountDownLatch(1)
        LocalHttpFixture().use { server ->
            server.route("/redirect") { exchange, _ ->
                exchange.responseHeaders.add("Location", "/video")
                exchange.sendResponseHeaders(302, -1)
            }
            server.route("/video") { exchange, request ->
                val attempt = calls.incrementAndGet()
                val range = request.headers["range"]?.single()
                when {
                    scenario == "clientError" -> exchange.respond(404, "missing".toByteArray())
                    scenario == "retry" && attempt == 1 -> exchange.respond(429, "retry".toByteArray())
                    scenario == "range416" && attempt == 1 -> exchange.respond(416, "stale partial".toByteArray())
                    scenario == "truncated" && attempt == 1 -> {
                        exchange.responseHeaders.add("Content-Type", "video/mp4")
                        exchange.sendResponseHeaders(200, media.size.toLong())
                        exchange.responseBody.write(media, 0, 8192)
                        exchange.responseBody.flush()
                        // Deliberately close short of Content-Length.
                    }
                    range != null && scenario != "ignoreRange" &&
                        !(scenario == "segmentFallback" && range != "bytes=0-0") -> {
                        val bounds = range.removePrefix("bytes=").split('-')
                        val start = bounds[0].toInt()
                        val end = bounds[1].toIntOrNull() ?: media.lastIndex
                        exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${media.size}")
                        exchange.respond(206, media.copyOfRange(start, end + 1),
                            if (scenario == "contentType") "text/html" else "video/mp4")
                    }
                    else -> exchange.respond(200,
                        if (scenario == "signature") ByteArray(media.size) else media,
                        if (scenario == "contentType") "text/html" else "video/mp4")
                }
            }
            server.start()
            val request = DownloadPlatformRequest(
                sourceUrl = "${server.url}/" + if (scenario == "redirect") "redirect" else "video",
                sourceHeaders = mapOf("X-Fixture" to "local-only", "Authorization" to "Bearer synthetic", "Accept-Encoding" to "gzip"),
                destinationFileName = name, destinationDirOverride = directory.toString(),
                maximumSizeBytes = if (scenario == "sizeLimit") 5000 else null,
            )
            val handle = DownloadsPlatformDownloader.start(request,
                onProgress = { bytes, total ->
                    progress += bytes to total
                    if (scenario == "cancel") {
                        progressEntered.countDown()
                        check(cancelReleased.await(10, TimeUnit.SECONDS)) { "Cancellation gate timed out" }
                    }
                },
                onSuccess = { path, total -> success.incrementAndGet(); completed.complete(Result(path = path, total = total)) },
                onFailure = { error, transient -> failure.incrementAndGet(); completed.complete(Result(error = error, transient = transient)) },
            )
            try {
                if (scenario == "cancel") {
                    assertTrue(progressEntered.await(10, TimeUnit.SECONDS))
                    handle.cancel()
                    cancelReleased.countDown()
                    awaitStopped(handle)
                    assertFalse(completed.isDone)
                    assertEquals(0, success.get() + failure.get())
                    assertFalse(Files.exists(destination))
                    assertContentEquals(media.copyOfRange(0, 4096), Files.readAllBytes(partial))
                    assertTrue(DownloadsPlatformDownloader.removePartialFile(name, directory.toString()))
                    assertFalse(Files.exists(partial))
                } else {
                    val result = completed.get(25, TimeUnit.SECONDS)
                    awaitStopped(handle)
                    if (scenario in setOf("signature", "contentType", "sizeLimit", "clientError")) {
                        assertNotNull(result.error)
                        assertFalse(result.transient)
                        assertEquals(0, success.get())
                        assertEquals(1, failure.get())
                        assertFalse(Files.exists(destination))
                        assertFalse(Files.exists(partial))
                    } else {
                        assertNull(result.error)
                        assertEquals(destination.toAbsolutePath().toString(), result.path)
                        assertEquals(media.size.toLong(), result.total)
                        assertEquals(1, success.get())
                        assertEquals(0, failure.get())
                        assertContentEquals(media, Files.readAllBytes(destination))
                        assertTrue(progress.isNotEmpty())
                        assertEquals(media.size.toLong(), progress.last().first)
                        assertFalse(Files.exists(partial))
                        assertEquals(destination.toAbsolutePath().toString(), DownloadsPlatformDownloader.resolveLocalFileUri(
                            result.path, name, directory.toString()))
                    }
                }
                val seen = server.drainRequests()
                assertTrue(seen.isNotEmpty())
                seen.forEach {
                    assertEquals(listOf("local-only"), it.headers["x-fixture"])
                    assertEquals(listOf("Bearer synthetic"), it.headers["authorization"])
                    assertEquals(listOf("identity"), it.headers["accept-encoding"])
                }
                val videoRequests = seen.filter { it.path == "/video" }
                when (scenario) {
                    "resume", "ignoreRange" -> assertEquals(listOf("bytes=4096-"), videoRequests.single().headers["range"])
                    "range416" -> {
                        assertEquals(2, videoRequests.size)
                        assertEquals(listOf("bytes=4096-"), videoRequests[0].headers["range"])
                        assertNull(videoRequests[1].headers["range"])
                    }
                    "retry", "truncated" -> {
                        assertEquals(2, videoRequests.size)
                        if (scenario == "truncated") assertEquals(listOf("bytes=8192-"), videoRequests.last().headers["range"])
                    }
                    "clientError" -> assertEquals(1, videoRequests.size)
                    "segments", "segmentFallback" -> {
                        val ranges = videoRequests.mapNotNull { it.headers["range"]?.single() }
                        assertEquals(5, ranges.size, "One capability probe and four real segment requests required")
                        assertContains(ranges, "bytes=0-0")
                        val segments = ranges.filterNot { it == "bytes=0-0" }.map {
                            val parts = it.removePrefix("bytes=").split('-')
                            parts[0].toInt()..parts[1].toInt()
                        }.sortedBy { it.first }
                        assertEquals(0, segments.first().first)
                        assertEquals(media.lastIndex, segments.last().last)
                        segments.zipWithNext().forEach { (left, right) -> assertEquals(left.last + 1, right.first) }
                        assertEquals(if (scenario == "segments") 1 else 2, videoRequests.count { it.headers["range"] == null })
                        assertEquals(listOf(name), Files.list(directory).use { paths -> paths.map { it.fileName.toString() }.toList() })
                    }
                }
                server.assertHealthy()
            } finally {
                cancelReleased.countDown()
                handle.cancel()
                awaitStopped(handle)
            }
        }
    }
}
