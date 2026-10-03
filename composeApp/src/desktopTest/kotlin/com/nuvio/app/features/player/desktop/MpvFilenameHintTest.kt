package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class MpvFilenameHintTest {
    private val cdn = "https://store-046.wnam.tb-cdn.io/dld/abc123?token=SECRET"

    @Test
    fun debridLinkGetsReadableBasename() {
        assertEquals(
            "$cdn#/Show%20S01E01%201080p.mkv",
            withMpvFilenameHint(cdn, "Show S01E01 1080p.mkv"),
        )
    }

    @Test
    fun pathSeparatorsInTitleCannotChangeBasename() {
        assertEquals("$cdn#/a%20b%20c", withMpvFilenameHint(cdn, "a/b\\c"))
    }

    @Test
    fun leavesOtherUrlsAlone() {
        assertEquals(cdn, withMpvFilenameHint(cdn, " "))
        assertEquals("https://example.com/a.mkv", withMpvFilenameHint("https://example.com/a.mkv", "T"))
        assertEquals("file:///a.mkv", withMpvFilenameHint("file:///a.mkv", "T"))
        val hls = "https://store-1.wnam.tb-cdn.io/x/master.m3u8?t=1"
        assertEquals(hls, withMpvFilenameHint(hls, "T"))
        assertEquals("$cdn#x", withMpvFilenameHint("$cdn#x", "T"))
    }
}
