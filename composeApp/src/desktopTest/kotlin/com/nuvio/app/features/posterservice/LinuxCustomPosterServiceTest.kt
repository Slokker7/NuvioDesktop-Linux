package com.nuvio.app.features.posterservice

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.testing.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LinuxCustomPosterServiceTest {
    private fun probe(case: String) = withHeadlessFixture { runHeadlessProbe(it, LinuxCustomPosterServiceProbe::class.java, case) }
    @Test fun templatesAndScreensSurviveFreshProcess() = withHeadlessFixture {
        runHeadlessProbe(it, LinuxCustomPosterServiceProbe::class.java, "write")
        runHeadlessProbe(it, LinuxCustomPosterServiceProbe::class.java, "read")
    }
    @Test fun metadataUsesBothShapesAndKeepsFallback() = probe("shapes")
    @Test fun excludedScreenKeepsOriginalMetadata() = probe("screen")
    @Test fun landscapeCardDoesNotReceivePortraitArt() = probe("landscape")
    @Test fun shapePlaceholderSuppliesLandscapeFallback() = probe("fallback")
    @Test fun clearingTemplatesDisablesService() = probe("clear")
    @Test fun localhostProbeAcceptsBothShapes() = probe("success")
    @Test fun unavailableRequiredIdDoesNotMakeRequest() = probe("unsupported")
    @Test fun malformedUrlProducesFailure() = probe("malformed")
    @Test fun httpErrorReportsBoundedServerDetail() = probe("error")
}

internal object LinuxCustomPosterServiceProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val scenario = args[1]
        val repository = CustomPosterSettingsRepository
        val storedPortrait = "http://127.0.0.1:1/poster/{imdb_id}"
        val storedLandscape = "http://127.0.0.1:1/landscape/{tmdb_id}"
        if (scenario == "read") {
            assertTrue(repository.snapshot().enabled)
            assertEquals(storedPortrait, repository.snapshot().posterUrlTemplate)
            assertEquals(storedLandscape, repository.snapshot().landscapeUrlTemplate)
            assertFalse(repository.snapshot(CustomPosterScreen.Search).enabled)
            assertTrue(repository.snapshot(CustomPosterScreen.Home).enabled)
            assertEquals(CustomPosterContinueWatchingMode.All, repository.snapshot().continueWatchingMode)
            return@runBlocking
        }
        repository.setPosterUrlTemplate(" $storedPortrait ")
        repository.setLandscapeUrlTemplate(" $storedLandscape ")
        repository.setEnabled(true)
        val original = MetaPreview("tt0347149", "movie", "Fixture", poster = "original-poster", landscapePoster = "original-landscape")
        when (scenario) {
            "write" -> {
                repository.setScreenEnabled(CustomPosterScreen.Search, false)
                repository.setContinueWatchingMode(CustomPosterContinueWatchingMode.All)
            }
            "shapes", "landscape" -> {
                val input = if (scenario == "landscape") original.copy(posterShape = PosterShape.Landscape) else original
                val transformed = input.withCustomPosters(repository.snapshot(CustomPosterScreen.Home), "tt0347149", 4935)
                assertEquals(if (scenario == "landscape") "original-poster" else "http://127.0.0.1:1/poster/tt0347149", transformed.poster)
                assertEquals("http://127.0.0.1:1/landscape/4935", transformed.landscapePoster)
                if (scenario == "shapes") assertEquals("original-poster", transformed.posterFallback)
            }
            "screen" -> {
                repository.setScreenEnabled(CustomPosterScreen.Search, false)
                assertEquals(original, original.withCustomPosters(repository.snapshot(CustomPosterScreen.Search), "tt0347149", 4935))
                assertNotEquals(original, original.withCustomPosters(repository.snapshot(CustomPosterScreen.Home), "tt0347149", 4935))
            }
            "fallback" -> {
                repository.setPosterUrlTemplate("http://127.0.0.1:1/{shape}/{imdb_id}")
                repository.setLandscapeUrlTemplate("")
                val transformed = original.withCustomPosters(repository.snapshot(), "tt0347149", 4935)
                assertEquals("http://127.0.0.1:1/poster/tt0347149", transformed.poster)
                assertEquals("http://127.0.0.1:1/landscape/tt0347149", transformed.landscapePoster)
            }
            "clear" -> {
                repository.setPosterUrlTemplate("")
                assertTrue(repository.snapshot().enabled)
                repository.setLandscapeUrlTemplate("")
                assertFalse(repository.snapshot().enabled)
                repository.setEnabled(true)
                assertFalse(repository.snapshot().enabled)
                assertEquals(original, original.withCustomPosters(repository.snapshot(), "tt0347149", 4935))
            }
            "success", "unsupported", "malformed", "error" -> LocalHttpFixture().use { server ->
                server.route("/") { exchange, _ ->
                    exchange.respond(if (scenario == "error") 403 else 200,
                        if (scenario == "error") "denied-".repeat(100).toByteArray() else "HTTP accepted; not an image".toByteArray())
                }
                server.start()
                repository.setPosterUrlTemplate(when (scenario) {
                    "unsupported" -> "${server.url}/{tvdb_id}"
                    "malformed" -> "not-a-url/{imdb_id}"
                    else -> "${server.url}/poster/{imdb_id}"
                })
                repository.setLandscapeUrlTemplate("${server.url}/landscape/{tmdb_id}")
                val result = probeCustomPosterTemplate(repository.snapshot(), CustomPosterShape.Portrait)
                when (scenario) {
                    "success" -> {
                        assertEquals(CustomPosterTemplateProbe.Ok, result)
                        assertEquals("/poster/tt0347149", server.nextRequest().path)
                        assertEquals(CustomPosterTemplateProbe.Ok, probeCustomPosterTemplate(repository.snapshot(), CustomPosterShape.Landscape))
                        val request = server.nextRequest()
                        assertEquals("/landscape/4935", request.path)
                        assertEquals("GET", request.method)
                        assertEquals("", request.body)
                    }
                    "error" -> {
                        val detail = assertIs<CustomPosterTemplateProbe.Failed>(result).detail
                        assertContains(detail, "HTTP 403: denied-")
                        assertTrue(detail.length < 250)
                        server.nextRequest()
                    }
                    else -> {
                        assertIs<CustomPosterTemplateProbe.Failed>(result)
                        assertTrue(server.drainRequests().isEmpty())
                    }
                }
            }
            else -> error("Unknown scenario $scenario")
        }
    }
}
