package com.nuvio.app.features.player

import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class ProviderDiagnosticVideoLinuxTest {
    private fun probe(case: String) = withHeadlessFixture { runHeadlessProbe(it, ProviderDiagnosticVideoLinuxProbe::class.java, case) }
    @Test fun smallMediaIsCachedAndReleased() = probe("valid")
    @Test fun completeRangeResponseIsAccepted() = probe("range")
    @Test fun invalidSignatureIsRejectedWithoutTemporaryFile() = probe("invalid")
    @Test fun oversizedDeclaredContentIsRejected() = probe("oversized")
    @Test fun dishonestRangeBodyIsBoundedAndRejected() = probe("bodyBound")
    @Test fun unknownRangeTotalIsRejected() = probe("unknownRange")
    @Test fun credentialsAreExcludedAcrossLocalRedirect() = probe("headers")
    @Test fun releaseOnlyDeletesTrackedFileAndIsIdempotent() = probe("tracked")
    @Test fun httpFailureLeavesNoTemporaryFile() = probe("failure")
    @Test fun truncatedBodyLeavesNoTemporaryFile() = probe("truncated")
}

internal object ProviderDiagnosticVideoLinuxProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val root = Path.of(args[0])
        val scenario = args[1]
        val temp = root.resolve("tmp")
        val media = byteArrayOf(0, 0, 0, 16) + "ftypisom0000".toByteArray()
        val seen = AtomicReference<Map<String, List<String>>>()
        val redirectSeen = AtomicReference<Map<String, List<String>>>()
        val resolverFinished = CountDownLatch(1)
        val bodyWaitExpired = AtomicBoolean(false)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect") { exchange ->
            redirectSeen.set(exchange.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.toList() })
            exchange.responseHeaders.add("Location", "/media")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/media") { exchange ->
            try {
                seen.set(exchange.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.toList() })
                exchange.responseHeaders.add("Content-Type", "video/mp4")
                val body = when (scenario) {
                    "invalid" -> "not-media-at-all".toByteArray()
                    "bodyBound" -> ByteArray(8 * 1024 * 1024 + 1)
                    else -> media
                }
                val code = when (scenario) {
                    "failure" -> 503
                    "range", "bodyBound", "unknownRange" -> 206
                    else -> 200
                }
                if (code == 206) exchange.responseHeaders.add("Content-Range", when (scenario) {
                    "unknownRange" -> "bytes 0-${media.size - 1}/*"
                    else -> "bytes 0-${media.size - 1}/${media.size}"
                })
                val size = when (scenario) {
                    "oversized" -> 8L * 1024 * 1024 + 1
                    "truncated" -> media.size.toLong() + 10
                    "bodyBound" -> 0L // Chunked: deliberately withhold EOF until the resolver returns.
                    else -> body.size.toLong()
                }
                exchange.sendResponseHeaders(code, size)
                exchange.responseBody.write(body)
                if (scenario == "bodyBound") {
                    exchange.responseBody.flush()
                    bodyWaitExpired.set(!resolverFinished.await(10, TimeUnit.SECONDS))
                }
            } catch (_: java.io.IOException) {
                // Expected when the bounded resolver closes an oversized/truncated response.
            } finally { exchange.close() }
        }
        server.start()
        val unrelated = temp.resolve("nuvio-provider-diagnostic-untracked.mp4")
        Files.write(unrelated, media)
        fun files() = Files.list(temp).use { it.map { path -> path.fileName.toString() }.sorted().toList() }
        val baseline = files()
        try {
            val url = "http://127.0.0.1:${server.address.port}/" + if (scenario == "headers") "redirect" else "media"
            val result = resolveProviderDiagnosticVideo(url, mapOf(
                "aUtHoRiZaTiOn" to "Bearer fixture-secret", "Cookie" to "fixture-cookie", "Proxy-Authorization" to "fixture-proxy",
                "Range" to "bytes=999-", "Accept" to "text/html", "Accept-Encoding" to "gzip", "Host" to "untrusted.invalid",
                "X-Fixture" to "allowed",
            ))
            if (scenario in setOf("valid", "range", "headers", "tracked")) {
                val path = Path.of(assertNotNull(result).sourceUrl)
                assertEquals(temp, path.parent)
                assertContentEquals(media, Files.readAllBytes(path))
                assertEquals(baseline.size + 1, files().size)
                releaseProviderDiagnosticVideo(unrelated.toString())
                assertTrue(Files.exists(unrelated))
                releaseProviderDiagnosticVideo(path.toString())
                assertFalse(Files.exists(path))
                releaseProviderDiagnosticVideo(path.toString())
                if (scenario == "tracked") {
                    Files.write(path, media)
                    releaseProviderDiagnosticVideo(path.toString())
                    assertTrue(Files.exists(path), "Released paths must no longer be tracked")
                    Files.delete(path)
                }
            } else assertNull(result)
            assertFalse(bodyWaitExpired.get(), "Bounded reading must finish without waiting for response EOF")
            val headers = assertNotNull(seen.get())
            if (scenario == "headers") {
                val firstRequest = assertNotNull(redirectSeen.get())
                listOf("authorization", "cookie", "proxy-authorization").forEach { assertFalse(firstRequest.containsKey(it)) }
            }
            listOf("authorization", "cookie", "proxy-authorization").forEach { assertFalse(headers.containsKey(it)) }
            assertEquals(listOf("allowed"), headers["x-fixture"])
            assertEquals(listOf("bytes=0-8388607"), headers["range"])
            assertFalse(headers["host"].orEmpty().contains("untrusted.invalid"))
            assertEquals(baseline, files(), "Rejected/released media must not leave cache files")
        } finally { resolverFinished.countDown(); server.stop(0) }
    }
}
