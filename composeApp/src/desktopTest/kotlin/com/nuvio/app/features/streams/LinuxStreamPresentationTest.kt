package com.nuvio.app.features.streams

import com.nuvio.app.features.debrid.*
import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import kotlinx.serialization.json.*
import kotlin.test.*

class LinuxStreamPresentationTest {
    private fun probe(case: String) = withHeadlessFixture { runHeadlessProbe(it, LinuxStreamPresentationProbe::class.java, case) }
    @Test fun persistedProfileDrivesPresentationInFreshProcess() = withHeadlessFixture {
        runHeadlessProbe(it, LinuxStreamPresentationProbe::class.java, "write")
        runHeadlessProbe(it, LinuxStreamPresentationProbe::class.java, "read")
    }
    @Test fun disabledScoringKeepsProviderOrder() = probe("disabledScore")
    @Test fun disabledDebridReturnsOriginalGroups() = probe("disabledDebrid")
    @Test fun persistedPointsDetermineRanking() = probe("rank")
    @Test fun tiedScoresKeepInputOrder() = probe("ties")
    @Test fun minimumScoreExcludesRejectedStreams() = probe("minimum")
    @Test fun preferencesFilterBeforeScoring() = probe("filter")
    // Regression: top-level language metadata must reach the shared preference filters.
    @Test fun parsedHdrLanguageAndAudioReachPresentationFilters() = probe("metadataFilters")
    @Test fun movieContextUsesMovieSizeBand() = probe("movie")
    @Test fun episodeContextUsesEpisodeSizeBand() = probe("episode")
    @Test fun formattingPreservesPassthroughAndPlaybackFields() = probe("format")
}

internal object LinuxStreamPresentationProbe {
    private val base = DebridSettings(enabled = true,
        providerApiKeys = mapOf(DebridProviders.PREMIUMIZE_ID to "fixture-only"),
        streamNameTemplate = "", streamDescriptionTemplate = "")

    private fun parsed(filename: String, size: Long = 12_000_000_000, language: String? = null): StreamItem {
        val payload = buildJsonObject {
            putJsonArray("streams") { add(buildJsonObject {
                put("name", filename)
                put("description", "Original description")
                if (language != null) put("language", language)
                putJsonObject("clientResolve") {
                    put("type", "debrid"); put("service", DebridProviders.PREMIUMIZE_ID)
                    put("filename", filename); put("isCached", true)
                    putJsonObject("stream") { putJsonObject("raw") { put("filename", filename); put("size", size) } }
                }
            }) }
        }
        return StreamParser.parse(payload.toString(), "Fixture", "addon:fixture").single()
    }

    private fun present(streams: List<StreamItem>, settings: DebridSettings = base,
        context: StreamScoreContext = StreamScoreContext.MOVIE): List<StreamItem> =
        DebridStreamPresentation.apply(listOf(AddonStreamGroup("Fixture", "addon:fixture", streams)),
            settings, StreamScoring(StreamScoreRepository.profile, context)).single().streams

    @JvmStatic fun main(args: Array<String>) {
        val scenario = args[1]
        val low = parsed("Film.720p.WEB-DL.x264-LOW.mkv")
        val high = parsed("Film.2160p.BluRay.REMUX.HEVC-HIGH.mkv", 40_000_000_000)
        val mid = parsed("Film.1080p.WEB-DL.x264-MID.mkv")
        val points = mapOf(StreamScoreTrait.QUALITY_2160P_REMUX.id to 90,
            StreamScoreTrait.QUALITY_1080P_WEB_DL.id to 50,
            StreamScoreTrait.QUALITY_720P_WEB_DL.id to -10)
        if (scenario != "read") {
            StreamScoreRepository.update { StreamScoreProfile(enabled = true, points = points, minimumScore = null) }
            StreamScoreRepository.reload()
            assertEquals(points, StreamScoreRepository.profile.points)
        }
        when (scenario) {
            "write" -> return
            "read", "rank" -> {
                assertTrue(StreamScoreRepository.profile.enabled)
                assertEquals(points, StreamScoreRepository.profile.points)
                assertEquals(listOf(high, mid, low), present(listOf(low, high, mid)))
            }
            "disabledScore" -> {
                StreamScoreRepository.update { it.copy(enabled = false) }
                StreamScoreRepository.reload()
                assertEquals(listOf(low, high, mid), present(listOf(low, high, mid)))
                assertEquals(StreamScore.NEUTRAL, StreamScorer.score(high, StreamScoreRepository.profile, StreamScoreContext.MOVIE))
            }
            "disabledDebrid" -> {
                val groups = listOf(AddonStreamGroup("Fixture", "addon:fixture", listOf(low, high)))
                assertSame(groups, DebridStreamPresentation.apply(groups, base.copy(enabled = false),
                    StreamScoring(StreamScoreRepository.profile, StreamScoreContext.MOVIE)))
            }
            "ties" -> {
                StreamScoreRepository.update { it.copy(points = emptyMap()) }
                StreamScoreRepository.reload()
                assertEquals(listOf(mid, low, high), present(listOf(mid, low, high)))
            }
            "minimum" -> {
                StreamScoreRepository.update { it.copy(minimumScore = 0) }
                StreamScoreRepository.reload()
                assertEquals(listOf(high, mid), present(listOf(low, high, mid)))
            }
            "filter" -> {
                val hdr = parsed("Film.2160p.BluRay.REMUX.DV.HDR10.HEVC-HIGH.mkv", 40_000_000_000)
                val streams = listOf(low, hdr, mid)
                val cases = listOf(
                    DebridStreamPreferences(excludedResolutions = listOf(DebridStreamResolution.P2160)) to listOf(mid, low),
                    DebridStreamPreferences(excludedEncodes = listOf(DebridStreamEncode.HEVC)) to listOf(mid, low),
                    DebridStreamPreferences(excludedReleaseGroups = listOf("HIGH")) to listOf(mid, low),
                    DebridStreamPreferences(sizeMaxGb = 20) to listOf(mid, low),
                    DebridStreamPreferences(maxResults = 1) to listOf(hdr),
                )
                cases.forEach { (preferences, expected) -> assertEquals(expected, present(streams, base.copy(streamPreferences = preferences))) }
            }
            "metadataFilters" -> {
                val rich = parsed("Film.2160p.BluRay.REMUX.DV.HDR10.TrueHD.Atmos.7.1.HEVC-HIGH.mkv", 40_000_000_000, "en")
                val plain = parsed("Film.1080p.WEB-DL.AAC.2.0.x264-MID.mkv", 12_000_000_000, "ja")
                val streams = listOf(plain, rich)
                val cases = listOf(
                    DebridStreamPreferences(excludedVisualTags = listOf(DebridStreamVisualTag.DV, DebridStreamVisualTag.HDR_DV, DebridStreamVisualTag.HDR10)) to listOf(plain),
                    DebridStreamPreferences(excludedQualities = listOf(DebridStreamQuality.BLURAY_REMUX)) to listOf(plain),
                    DebridStreamPreferences(requiredAudioChannels = listOf(DebridStreamAudioChannel.CH_7_1)) to listOf(rich),
                    DebridStreamPreferences(requiredAudioTags = listOf(DebridStreamAudioTag.ATMOS)) to listOf(rich),
                    DebridStreamPreferences(requiredLanguages = listOf(DebridStreamLanguage.JA)) to listOf(plain),
                    DebridStreamPreferences(excludedLanguages = listOf(DebridStreamLanguage.EN)) to listOf(plain),
                )
                cases.forEach { (preferences, expected) -> assertEquals(expected, present(streams, base.copy(streamPreferences = preferences))) }
            }
            "movie", "episode" -> {
                StreamScoreRepository.update { it.copy(points = mapOf(StreamScoreTrait.SIZE_IN_BAND.id to 20,
                    StreamScoreTrait.SIZE_FAR_OUT.id to -50), sizeBand = StreamSizeBand(enabled = true)) }
                StreamScoreRepository.reload()
                val small = parsed("Show.S01E01.1080p.WEB-DL.x264-NTb.mkv", 2_000_000_000)
                val large = parsed("Film.1080p.WEB-DL.x264-NTb.mkv", 12_000_000_000)
                val context = if (scenario == "episode") StreamScoreContext.EPISODE else StreamScoreContext.MOVIE
                assertEquals(if (scenario == "episode") listOf(small, large) else listOf(large, small), present(listOf(small, large), context = context))
                assertEquals(20, StreamScorer.score(if (scenario == "episode") small else large, StreamScoreRepository.profile, context).total)
            }
            "format" -> {
                val passthrough = StreamParser.parse("""{"streams":[{"name":"Direct fixture","url":"http://127.0.0.1:1/video"}]}""", "Fixture", "addon:fixture").single()
                val output = present(listOf(passthrough, high), base.copy(streamNameTemplate = "{stream.resolution} {service.shortName}",
                    streamDescriptionTemplate = "{stream.filename}"))
                assertEquals("2160p PM", output.first().name)
                assertEquals(high.clientResolve?.filename, output.first().description)
                assertEquals(high.clientResolve, output.first().clientResolve)
                assertEquals(high.behaviorHints, output.first().behaviorHints)
                assertEquals(passthrough, output.last())
            }
            else -> error("Unknown scenario $scenario")
        }
    }
}
