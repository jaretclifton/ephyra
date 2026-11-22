package dev.jdtech.jellyfin.repository

import android.app.Application
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.MediaStreamType
import dev.jdtech.jellyfin.models.SubtitleDeliveryMethod
import dev.jdtech.jellyfin.models.toItemId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Architecture 0.4 (#738): pins what the app makes of a playback info answer
 * (`MediaSourceInfo.toFindroidSource`): which path plays, whether it is a transcode, and the
 * subtitle paths. The answers are recorded from the test server (`resources/playback/`); the HTTP
 * one is the file answer with its source moved to HTTP, as the test server has no remote item.
 *
 * No current server is set, so no download is read or added.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaybackSourceMappingTest {
    private val itemId = "8f086228-6a92-020d-0cb7-35d734765afe".toItemId()
    // Set on the test thread, read on the server thread.
    @Volatile private var answer = ""
    private val server = StandInServer { request ->
        if (request.path.endsWith("/PlaybackInfo")) StandInServer.resource(answer) else null
    }
    private val repository by lazy { server.repository() }

    @After
    fun stop() {
        server.close()
    }

    private suspend fun playbackSource(answer: String): FindroidSource {
        this.answer = answer
        return repository.getPlaybackSources(itemId, StandInServer.LOCAL_PLAYER).single()
    }

    private suspend fun downloadSource(answer: String, includePath: Boolean): FindroidSource {
        this.answer = answer
        return repository.getMediaSources(itemId, includePath).single()
    }

    @Test
    fun `a source with a transcoding URL plays the transcode`() = runBlocking {
        val source = playbackSource(TRANSCODE)

        assertTrue(source.transcoded)
        assertEquals(
            "${server.baseUrl}/videos/8f086228-6a92-020d-0cb7-35d734765afe/master.m3u8?" +
                "DeviceId=test&MediaSourceId=$SOURCE_ID&VideoCodec=h264&AudioCodec=aac" +
                "&AudioStreamIndex=2&VideoBitrate=999808000&AudioBitrate=192000&MaxFramerate=24" +
                "&SegmentContainer=ts&PlaySessionId=$TRANSCODE_SESSION&ApiKey=token" +
                "&RequireAvc=false&EnableAudioVbrEncoding=true&Tag=0f013865a99e94399ff8b5f3354d44d8" +
                "&h264-level=31&h264-videobitdepth=8&h264-profile=high" +
                "&TranscodeReasons=AudioCodecNotSupported,DirectPlayError",
            source.path,
            message = "the server's relative URL joined to the base URL",
        )
        assertEquals(TRANSCODE_SESSION, source.playSessionId)
        assertEquals(SOURCE_ID, source.id)
        assertEquals("Libass Sidecar Movie (2026)", source.name)
        assertEquals(FindroidSourceType.REMOTE, source.type)
        assertEquals(46_587_363L, source.size)
        assertEquals("mkv", source.container, message = "the file's container, not the transcode's")
        assertEquals(3_105_229, source.bitrate)
        assertEquals(2, source.defaultAudioStreamIndex)
    }

    @Test
    fun `a file source plays the static stream`() = runBlocking {
        val source = playbackSource(DIRECT_FILE)

        assertFalse(source.transcoded)
        assertEquals(
            "${server.baseUrl}/Videos/8f086228-6a92-020d-0cb7-35d734765afe/stream?" +
                "static=true&mediaSourceId=$SOURCE_ID&streamOptions=%7B%7D" +
                "&enableAudioVbrEncoding=true",
            source.path,
            // The SDK's URL, as it builds it: without credentials in the query.
            message = "the static stream URL",
        )
        assertNull(source.playSessionId, message = "a direct stream has no transcode to end")
    }

    @Test
    fun `an HTTP source plays its own path`() = runBlocking {
        val source = playbackSource(DIRECT_HTTP)

        assertFalse(source.transcoded)
        assertEquals("https://cdn.example.com/movie.mkv", source.path)
    }

    @Test
    fun `a subtitle delivered as a file gets its URL on the server`() = runBlocking {
        val streams = playbackSource(DIRECT_FILE).mediaStreams

        val subtitle = streams.single { it.type == MediaStreamType.SUBTITLE }
        assertEquals(
            "${server.baseUrl}/Videos/8f086228-6a92-020d-0cb7-35d734765afe/$SOURCE_ID" +
                "/Subtitles/0/0/Stream.ass?ApiKey=token",
            subtitle.path,
        )
        assertEquals(SubtitleDeliveryMethod.EXTERNAL, subtitle.deliveryMethod)
        assertTrue(subtitle.isExternal)
        assertEquals(0, subtitle.index)
        assertEquals(
            listOf(null, null),
            streams.filter { it.type != MediaStreamType.SUBTITLE }.map { it.path },
            message = "the video and audio stream have no delivery URL",
        )
    }

    @Test
    fun `a download source carries no play session`() = runBlocking {
        val source = downloadSource(TRANSCODE, includePath = true)

        assertTrue(source.transcoded, message = "a progressive transcode is downloaded")
        assertNull(source.playSessionId, message = "only the playback path keeps the session")
    }

    @Test
    fun `a file source without includePath has no path`() = runBlocking {
        val source = downloadSource(DIRECT_FILE, includePath = false)

        assertFalse(source.transcoded)
        assertEquals("", source.path)
    }

    private companion object {
        const val DIRECT_FILE = "playback/direct-file.json"
        const val DIRECT_HTTP = "playback/direct-http.json"
        const val TRANSCODE = "playback/transcode.json"
        const val SOURCE_ID = "8f0862286a92020d0cb735d734765afe"
        const val TRANSCODE_SESSION = "51c3f794f3da47dcb92c5c42f43f8c88"
    }
}
