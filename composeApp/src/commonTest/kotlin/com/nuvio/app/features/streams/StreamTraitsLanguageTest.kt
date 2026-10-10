package com.nuvio.app.features.streams

import com.nuvio.app.features.debrid.DebridStreamLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamTraitsLanguageTest {
    private fun parse(fields: String): StreamItem = StreamParser.parse(
        """{"streams":[{"url":"https://example.invalid/video.mkv",$fields}]}""",
        addonName = "Fixture",
        addonId = "fixture",
    ).single()

    @Test
    fun metadataOnlyJapaneseReachesDetectionAndPreferredLanguageScoring() {
        val stream = parse(""""language":"ja"""")
        assertEquals(listOf("ja"), stream.audioLanguages)
        assertEquals(listOf(DebridStreamLanguage.JA), StreamTraitDetector.detect(stream).languages)
        val profile = StreamScoreProfile(
            enabled = true,
            points = mapOf(StreamScoreTrait.LANGUAGE_PREFERRED.id to 25),
        )
        assertEquals(25, StreamScorer.score(stream, profile,
            StreamScoreContext(preferredLanguages = listOf("JA"))).total)
        assertEquals(0, StreamScorer.score(stream, profile,
            StreamScoreContext(preferredLanguages = listOf("en"))).total)
    }

    @Test
    fun titleAndFilenameDetectionStillWorkWithoutMetadata() {
        listOf(
            """"title":"Film.JA.1080p"""",
            """"behaviorHints":{"filename":"Film.JA.1080p.mkv"}""",
        ).forEach { fields ->
            assertEquals(listOf(DebridStreamLanguage.JA), StreamTraitDetector.detect(parse(fields)).languages)
        }
    }

    @Test
    fun codesAndLabelsNormalizeAndDeduplicateAcrossMetadataSources() {
        val stream = parse("""
            "language":"ja", "languages":["JA","Japanese","ja"],
            "clientResolve":{"stream":{"raw":{"parsed":{"languages":["JAPANESE","ja"]}}}}
        """.trimIndent())
        assertEquals(listOf(DebridStreamLanguage.JA), StreamTraitDetector.detect(stream).languages)
    }

    @Test
    fun multipleTopLevelLanguagesRemainDistinctCanonicalLanguages() {
        val stream = parse(""""language":"ja", "languages":["EN","Japanese","French","en"]""")
        assertEquals(listOf(DebridStreamLanguage.EN, DebridStreamLanguage.JA, DebridStreamLanguage.FR),
            StreamTraitDetector.detect(stream).languages)
    }

    @Test
    fun topLevelLanguagesSupplementExistingParsedLanguages() {
        val stream = parse("""
            "languages":["ja","French"],
            "clientResolve":{"stream":{"raw":{"parsed":{"languages":["English","JA"]}}}}
        """.trimIndent())
        assertEquals(listOf(DebridStreamLanguage.EN, DebridStreamLanguage.JA, DebridStreamLanguage.FR),
            StreamTraitDetector.detect(stream).languages)
    }

    @Test
    fun unrecognizedMetadataKeepsTextFallback() {
        val stream = parse(""""language":"not-a-language", "title":"Film.JA.1080p"""")
        assertEquals(listOf(DebridStreamLanguage.JA), StreamTraitDetector.detect(stream).languages)
    }

    @Test
    fun recognizedStructuredLanguagesKeepPrecedenceOverTextFallback() {
        val stream = parse("""
            "title":"Film.EN.1080p",
            "clientResolve":{"stream":{"raw":{"parsed":{"languages":["Japanese"]}}}}
        """.trimIndent())
        assertEquals(listOf(DebridStreamLanguage.JA), StreamTraitDetector.detect(stream).languages)
    }

    @Test
    fun addingLanguageMetadataLeavesEveryOtherTraitUnchanged() {
        val stream = parse("""
            "name":"Film.2160p.BluRay.REMUX.DV.HDR10.TrueHD.Atmos.7.1.HEVC-NTb.mkv",
            "behaviorHints":{"videoSize":40000000000}
        """.trimIndent())
        val original = StreamTraitDetector.detect(stream)
        val withLanguage = StreamTraitDetector.detect(stream.copy(audioLanguages = listOf("ja")))
        assertEquals(original.copy(languages = listOf(DebridStreamLanguage.JA)), withLanguage)
    }
}
