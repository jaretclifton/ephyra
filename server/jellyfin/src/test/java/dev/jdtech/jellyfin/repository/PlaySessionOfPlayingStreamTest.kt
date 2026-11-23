package dev.jdtech.jellyfin.repository

import android.app.Application
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.toItemId
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CE (#728): the playback reports name the play session of the stream the player loaded, not of the
 * last playback info answer. A quality switch asks for new streams for the whole queue and swaps
 * them in only when every request succeeded; one that fails keeps the old streams, so the answer it
 * got for the playing item must not change the reports. Before, a failed switch to Original made
 * the reports of a playing transcode say DirectPlay, and the server showed that.
 *
 * The server here answers each playback info request with the next answer of [answers] and records
 * the body of each start report.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaySessionOfPlayingStreamTest {
    private val itemId = "00000000-0000-0000-0000-000000000040".toItemId()
    private val answers = Collections.synchronizedList(mutableListOf<String>())
    private val server = StandInServer { request ->
        if (request.path.contains("/PlaybackInfo")) answers.removeAt(0) else null
    }

    private val starts: List<JsonObject>
        get() = server.requests.filter { it.path == "/Sessions/Playing" }.map { it.json() }

    @After
    fun stop() {
        server.close()
    }

    // No current server is set, so the playback info request reads no downloads.
    private val repository by lazy { server.repository() }

    /** The playback info answer: a transcode with [session], or the file itself without one. */
    private fun answer(session: String?): String {
        val transcoding =
            if (session == null) """"TranscodingSubProtocol":"http","""
            else
                """"TranscodingUrl":"/videos/${itemId.asString()}/master.m3u8?PlaySessionId=$session",""" +
                    """"TranscodingSubProtocol":"hls","""
        return """{"MediaSources":[{"Id":"source","Protocol":"Http","Path":"http://file",""" +
            """"Type":"Default",$transcoding"IsRemote":false,"ReadAtNativeFramerate":false,""" +
            """"IgnoreDts":false,"IgnoreIndex":false,"GenPtsInput":false,""" +
            """"SupportsTranscoding":true,"SupportsDirectStream":false,""" +
            """"SupportsDirectPlay":${session == null},"IsInfiniteStream":false,""" +
            """"UseMostCompatibleTranscodingProfile":false,"RequiresOpening":false,""" +
            """"RequiresClosing":false,"RequiresLooping":false,"SupportsProbing":false,""" +
            """"MediaStreams":[],"HasSegments":false}],""" +
            """"PlaySessionId":"${session ?: "direct"}"}"""
    }

    /** Asks for the item's stream, as a switch does, and loads it in the player if [load]. */
    private suspend fun request(session: String?, load: Boolean) {
        answers += answer(session)
        val source = repository.getPlaybackSources(itemId, StandInServer.LOCAL_PLAYER).single()
        assertEquals(session, source.playSessionId, message = "the source carries its session")
        if (load) repository.setPlaySession(itemId, source.playSessionId, source.transcoded)
    }

    private fun startReport(): Pair<String?, String?> {
        val start = starts.last()
        return start["PlayMethod"]?.jsonPrimitive?.content to
            start["PlaySessionId"]?.jsonPrimitive?.takeIf { it.isString }?.content
    }

    @Test
    fun `a switch to Original that fails keeps reporting the transcode that plays`() = runBlocking {
        request(session = "playing", load = true)
        request(session = null, load = false)

        repository.postPlaybackStart(itemId, positionTicks = 0L, transcoded = true)

        assertEquals(
            "Transcode" to "playing",
            startReport(),
            message = "the stream that plays is still the transcode",
        )
    }

    @Test
    fun `a switch to another transcode that fails keeps the session that plays`() = runBlocking {
        request(session = "playing", load = true)
        request(session = "never-played", load = false)

        repository.postPlaybackStart(itemId, positionTicks = 0L, transcoded = true)

        assertEquals("Transcode" to "playing", startReport(), message = "no job runs for the other")
    }

    @Test
    fun `the stream the player loads names its session`() = runBlocking {
        request(session = "first", load = true)
        request(session = "second", load = true)

        repository.postPlaybackStart(itemId, positionTicks = 0L, transcoded = true)

        assertEquals("Transcode" to "second", startReport(), message = "the new stream plays")
    }

    @Test
    fun `a stream that plays directly names no session`() = runBlocking {
        request(session = "first", load = true)
        request(session = null, load = true)

        repository.postPlaybackStart(itemId, positionTicks = 0L, transcoded = false)

        val (method, session) = startReport()
        assertEquals("DirectPlay", method, message = "the file plays")
        assertNull(session, message = "a direct stream has no transcode to name")
    }
}
