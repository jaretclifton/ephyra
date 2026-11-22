package dev.jdtech.jellyfin.presentation.utils

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * #769: the download quality dialog offers the whole ladder when the server can transcode a
 * download down to a cap (Jellyfin), and the original only when it cannot.
 */
class DownloadQualityChoicesTest {
    @Test
    fun `a server that caps downloads gets the whole ladder`() {
        assertEquals(VideoQualities.bitrates, VideoQualities.downloadChoices(capped = true))
    }

    @Test
    fun `a server that cannot gets the original only`() {
        assertEquals(listOf(0), VideoQualities.downloadChoices(capped = false))
    }
}
