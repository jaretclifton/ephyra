package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class PlaySessionsTest {
    private val item = "00000000-0000-0000-0000-000000000001".toItemId()
    private val other = "00000000-0000-0000-0000-000000000002".toItemId()
    private val sessions = PlaySessions()

    @Test
    fun `a transcoded item keeps its play session for the stop report`() {
        sessions.onPlaybackInfo(item, "abc", transcodes = true)

        assertEquals("abc", sessions.sessionOf(item))
        // Still there after a stop: Previous can go back to the stream the player already has.
        assertEquals("abc", sessions.sessionOf(item))
        assertNull(sessions.sessionOf(other))
    }

    @Test
    fun `the version of the stream is kept for the reports, and replaced by the next stream`() {
        // #909: a direct play keeps no session, but still its version.
        sessions.onPlaybackInfo(item, null, transcodes = false, "360p".toMediaSourceId())
        assertEquals("360p".toMediaSourceId(), sessions.sourceOf(item))
        assertNull(sessions.sourceOf(other))

        sessions.onPlaybackInfo(item, "abc", transcodes = true, "720p".toMediaSourceId())
        assertEquals("720p".toMediaSourceId(), sessions.sourceOf(item))

        // A stream without a known version names none, rather than the one before it.
        sessions.onPlaybackInfo(item, null, transcodes = false)
        assertNull(sessions.sourceOf(item))
    }

    @Test
    fun `an item that plays directly has no session to end`() {
        sessions.onPlaybackInfo(item, "abc", transcodes = false)

        assertNull(sessions.sessionOf(item))
    }

    @Test
    fun `a new request replaces the session`() {
        sessions.onPlaybackInfo(item, "abc", transcodes = true)
        sessions.onPlaybackInfo(item, "def", transcodes = true)
        assertEquals("def", sessions.sessionOf(item))

        // The quality cap was lifted and the item plays directly again.
        sessions.onPlaybackInfo(item, "ghi", transcodes = false)
        assertNull(sessions.sessionOf(item))
    }

    @Test
    fun `a missing or blank session is not kept`() {
        sessions.onPlaybackInfo(item, null, transcodes = true)
        assertNull(sessions.sessionOf(item))

        sessions.onPlaybackInfo(item, " ", transcodes = true)
        assertNull(sessions.sessionOf(item))
    }

    @Test
    fun `a transcode is reported as one, with its play session`() {
        sessions.onPlaybackInfo(item, "abc", transcodes = true)

        assertEquals(PlayReport(transcoded = true, "abc"), sessions.playReport(item, true))
    }

    @Test
    fun `an item that plays directly is reported as direct play`() {
        sessions.onPlaybackInfo(item, "abc", transcodes = false)

        assertEquals(PlayReport(transcoded = false, null), sessions.playReport(item, false))
    }

    @Test
    fun `a stream that is not the transcode is direct play, whatever session is kept`() {
        // A downloaded copy plays, or another version: the kept session is not this stream's.
        sessions.onPlaybackInfo(item, "abc", transcodes = true)

        assertEquals(PlayReport(transcoded = false, null), sessions.playReport(item, false))
    }

    @Test
    fun `a transcode without a known session is direct play`() {
        // The server takes Transcode without a session for direct play anyway.
        assertEquals(PlayReport(transcoded = false, null), sessions.playReport(other, true))
    }

    @Test
    fun `the report follows a quality switch both ways`() {
        sessions.onPlaybackInfo(item, "abc", transcodes = true)
        sessions.onPlaybackInfo(item, "def", transcodes = true)
        assertEquals(PlayReport(transcoded = true, "def"), sessions.playReport(item, true))

        sessions.onPlaybackInfo(item, "ghi", transcodes = false)
        assertEquals(PlayReport(transcoded = false, null), sessions.playReport(item, false))
    }
}
