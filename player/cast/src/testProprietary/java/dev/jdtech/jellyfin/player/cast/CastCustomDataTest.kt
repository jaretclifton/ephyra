package dev.jdtech.jellyfin.player.cast

import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.MediaStream
import dev.jdtech.jellyfin.models.MediaStreamType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * #767: what a queue item keeps in the receiver's custom data, and how a session is picked up from
 * it after the app's process was gone: the same tracks as [castTracks] made, no URL and so no
 * token, and nothing at all from what an older version of the app wrote.
 */
class CastCustomDataTest {

    private val source =
        ItemSource(
            id = "abc",
            name = "Movie",
            type = ItemSourceType.REMOTE,
            path = "https://jellyfin.example/videos/1/master.m3u8?AudioStreamIndex=2&ApiKey=token",
            size = 1,
            mediaStreams =
                listOf(
                    stream(1, MediaStreamType.AUDIO, "English"),
                    stream(2, MediaStreamType.AUDIO, "Dutch"),
                    stream(
                        3,
                        MediaStreamType.SUBTITLE,
                        "English",
                        path = "https://jellyfin.example/Videos/1/3.vtt?ApiKey=token",
                    ),
                    stream(4, MediaStreamType.SUBTITLE, "Signs"),
                ),
            transcoded = true,
            defaultAudioStreamIndex = 1,
            playSessionId = "session",
        )

    @Test
    fun `read back, it gives the tracks castTracks gave`() {
        val tracks = castTracks(source, audioStreamIndex = 2, subtitleTrackId = 103)
        val data = CastCustomData.decode(CastCustomData.of(source, tracks).encode())!!

        assertEquals(tracks.subtitles to tracks.audio, data.tracks(2, 103))
        // Without an audio stream in the URL the source's default plays, as castTracks has it.
        assertEquals(
            castTracks(source, null, 103).let { it.subtitles to it.audio },
            data.tracks(null, 103),
        )
        // A subtitle the receiver could not get does not show.
        assertEquals(listOf(false, false), data.tracks(2, 104).first.map { it.selected })
    }

    @Test
    fun `read back, it gives the play session and whether it is a transcode`() {
        val data =
            CastCustomData.decode(
                CastCustomData.of(source, castTracks(source, null, null)).encode()
            )!!

        assertEquals("session", data.playSessionId)
        assertEquals(true, data.transcoded)
        assertEquals("abc", data.source().id)
        assertEquals(true, data.source().transcoded)
        assertEquals(1, data.source().defaultAudioStreamIndex)
    }

    @Test
    fun `it keeps no URL, so no token`() {
        val encoded = CastCustomData.of(source, castTracks(source, null, null)).encode()

        assertFalse("token" in encoded, encoded)
        assertFalse("http" in encoded, encoded)
    }

    @Test
    fun `what another version wrote is read as far as it goes`() {
        val data =
            CastCustomData.decode(
                """{"mediaSourceId":"abc","transcoded":true,"somethingNew":[1,2]}"""
            )!!

        assertEquals("abc", data.mediaSourceId)
        assertEquals(emptyList(), data.audio)
    }

    @Test
    fun `nothing readable is nothing to restore`() {
        assertNull(CastCustomData.decode(null))
        assertNull(CastCustomData.decode(""))
        assertNull(CastCustomData.decode("not json"))
        // The server's whole answer, as the app kept it before #767, has no source id here.
        assertNull(CastCustomData.decode("""{"MediaSources":[],"PlaySessionId":"x"}"""))
    }

    private fun stream(index: Int, type: MediaStreamType, title: String, path: String? = null) =
        MediaStream(
            title = title,
            displayTitle = null,
            language = "eng",
            type = type,
            codec = "aac",
            profile = null,
            isExternal = false,
            path = path,
            channelLayout = null,
            videoRangeType = null,
            height = null,
            width = null,
            videoDoViTitle = null,
            index = index,
        )
}
