package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MpvMediaTitleTest {
    @Test
    fun `source listing title wins over content fallback`() {
        assertEquals(
            "1080p WEB-DL | Korean Subs",
            preferredMpvMediaTitle(
                streamTitle = "1080p WEB-DL | Korean Subs",
                title = "Friendly Drama",
                episodeText = "S1 E2",
            ),
        )
    }

    @Test
    fun `real media file name wins over the source label`() {
        assertEquals(
            "Show.S01E01.1080p.WEB-DL.mkv",
            preferredMpvMediaTitle(
                streamTitle = "TorBox 1080p",
                title = "Show",
                episodeText = "S1 E1",
                streamFilename = "Show.S01E01.1080p.WEB-DL.mkv",
            ),
        )
    }

    @Test
    fun `blank file name falls back to the source label`() {
        assertEquals("TorBox 1080p", preferredMpvMediaTitle("TorBox 1080p", "Show", null, streamFilename = " "))
    }

    @Test
    fun `content and episode form the fallback without a stream label`() {
        assertEquals(
            "Friendly Drama S1 E2",
            preferredMpvMediaTitle("  ", "Friendly Drama", "S1 E2"),
        )
    }

    @Test
    fun `control characters cannot create an mpv option line`() {
        val title = preferredMpvMediaTitle("Release\nforce-media-title=token", null, null)

        assertEquals("Release force-media-title=token", title)
        assertFalse('\n' in title)
        assertFalse('\r' in title)
    }
}
