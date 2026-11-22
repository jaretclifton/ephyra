package dev.jdtech.jellyfin.player.cast

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * The content type the receiver is told, and the audio stream read back from a stream URL (#626).
 * The URLs themselves come from the server adapter (`ReceiverUrlsTest` in `data`, #767).
 */
class CastUrlsTest {

    private val base = "https://jellyfin.example"

    @Test
    fun `a transcode is HLS and a file is MP4`() {
        assertEquals("application/x-mpegurl", castContentType(isTranscoded = true))
        assertEquals("video/mp4", castContentType(isTranscoded = false))
    }

    @Test
    fun `the audio stream is read back from a stream URL`() {
        assertEquals(
            2,
            audioStreamIndexOf("$base/videos/1/master.m3u8?MediaSourceId=abc&AudioStreamIndex=2"),
        )
        // The server writes it as it likes.
        assertEquals(3, audioStreamIndexOf("$base/videos/1/master.m3u8?audiostreamindex=3&a=b"))
        assertEquals(4, audioStreamIndexOf("$base/videos/1/main.m3u8?a=b&AUDIOSTREAMINDEX=4#t=10"))
    }

    @Test
    fun `a stream URL that names no audio stream has none`() {
        assertNull(audioStreamIndexOf("$base/Videos/1/stream?static=true&mediaSourceId=abc"))
        assertNull(audioStreamIndexOf("$base/Videos/1/stream"))
        assertNull(audioStreamIndexOf("$base/videos/1/master.m3u8?AudioStreamIndex="))
        assertNull(audioStreamIndexOf("$base/videos/1/master.m3u8?AudioStreamIndex=two"))
        assertNull(audioStreamIndexOf("$base/videos/1/master.m3u8?SubtitleStreamIndex=2"))
        assertNull(audioStreamIndexOf("$base/videos/1/master.m3u8#AudioStreamIndex=2"))
        assertNull(audioStreamIndexOf(""))
    }
}
