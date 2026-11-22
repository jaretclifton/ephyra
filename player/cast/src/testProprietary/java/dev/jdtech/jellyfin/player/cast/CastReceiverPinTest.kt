package dev.jdtech.jellyfin.player.cast

import android.app.Application
import android.provider.Settings
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.player.core.domain.models.Track
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #767, step 1: pins, on the code before the move, the playback info request a Cast session sends
 * and what the receiver is handed from the answer (stream URL, content type, tracks). The request
 * bodies are written to `data/src/test/resources/playback/cast-request-*.json`, which the adapter's
 * request test compares with after the move. Temporary: it goes with the old code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CastReceiverPinTest {
    private val itemId = UUID.fromString("8f086228-6a92-020d-0cb7-35d734765afe")
    private val resources = File(System.getProperty("user.dir"), "../../data/src/test/resources")

    private val requests = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    private var answer = "playback/cast-direct.json"
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val baseUrl = "http://127.0.0.1:${socket.localPort}"

    init {
        System.setProperty("kotlin-logging-to-android-native", "true")
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: break
                connection.use {
                    val input = it.getInputStream()
                    val target = readLine(input).split(" ").getOrElse(1) { "" }
                    var length = 0
                    while (true) {
                        val header = readLine(input)
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            length = header.substringAfter(':').trim().toInt()
                        }
                    }
                    requests += target to String(input.readNBytes(length))
                    val body = File(resources, answer).readText()
                    it.getOutputStream()
                        .write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${body.toByteArray().size}\r\n" +
                                    "Connection: close\r\n\r\n$body")
                                .toByteArray()
                        )
                }
            }
        }
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1 || c == '\n'.code) break
            if (c != '\r'.code) line.append(c.toChar())
        }
        return line.toString()
    }

    @After fun stop() = socket.close()

    private val jellyfin by lazy {
        val context = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "test")
        JellyfinApi(context).apply {
            api.update(baseUrl = baseUrl, accessToken = "token")
            userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        }
    }

    private fun pinBody(name: String) {
        val (target, body) = requests.single()
        assertEquals("/Items/$itemId/PlaybackInfo", target.substringBefore('?'))
        val file = File(resources, "playback/$name")
        if (!file.exists()) file.writeText(body)
        assertEquals(file.readText(), body, name)
    }

    @Test
    fun `a file the receiver plays as it is`() = runBlocking {
        val info =
            requestCastPlaybackInfo(
                jellyfin,
                itemId,
                SOURCE_ID,
                audioStreamIndex = null,
                hevc = false,
                maxBitrateMbps = 0,
            )
        pinBody("cast-request-h264.json")

        val source = info.mediaSources.first()
        val url =
            castStreamUrl(
                baseUrl,
                itemId,
                source.id,
                source.transcodingUrl,
                jellyfin.api.accessToken,
            )
        assertEquals(
            "$baseUrl/Videos/$itemId/stream?static=true&mediaSourceId=$SOURCE_ID&api_key=token",
            url,
        )
        assertEquals("video/mp4", castContentType(!source.transcodingUrl.isNullOrBlank()))
        assertEquals(null, audioStreamIndexOf(url))
        assertEquals(103, defaultSubtitleTrackId(source))

        // As buildMedia for a new item: no audio choice, the server's subtitle.
        val tracks = castTracks(source, baseUrl, null, defaultSubtitleTrackId(source))
        assertEquals(receiverTracks(), tracks.receiverTracks)
        assertEquals(subtitles(selected = 103), tracks.subtitles)
        assertEquals(audio(selected = 1), tracks.audio)
        assertEquals(listOf(103L), tracks.activeTrackIds)
    }

    @Test
    fun `a transcode for another audio track on an HEVC receiver with a cap`() = runBlocking {
        answer = "playback/cast-transcode.json"
        val info =
            requestCastPlaybackInfo(
                jellyfin,
                itemId,
                SOURCE_ID,
                audioStreamIndex = 2,
                hevc = true,
                maxBitrateMbps = 8,
            )
        pinBody("cast-request-hevc-audio-capped.json")

        val source = info.mediaSources.first()
        assertEquals("51c3f794f3da47dcb92c5c42f43f8c88", info.playSessionId)
        val url =
            castStreamUrl(
                baseUrl,
                itemId,
                source.id,
                source.transcodingUrl,
                jellyfin.api.accessToken,
            )
        assertEquals(
            "$baseUrl/videos/$itemId/master.m3u8?DeviceId=test&MediaSourceId=$SOURCE_ID" +
                "&VideoCodec=h264&AudioCodec=aac&AudioStreamIndex=2&VideoBitrate=7808000" +
                "&AudioBitrate=192000&SegmentContainer=ts" +
                "&PlaySessionId=51c3f794f3da47dcb92c5c42f43f8c88&ApiKey=token" +
                "&TranscodeReasons=AudioCodecNotSupported",
            url,
        )
        assertEquals(
            "application/x-mpegurl",
            castContentType(!source.transcodingUrl.isNullOrBlank()),
        )
        assertEquals(2, audioStreamIndexOf(url))
        assertEquals(104, defaultSubtitleTrackId(source))

        // As buildMedia for an audio switch: the chosen audio, the subtitle that showed.
        val tracks = castTracks(source, baseUrl, 2, 103)
        assertEquals(receiverTracks(), tracks.receiverTracks)
        assertEquals(subtitles(selected = 103), tracks.subtitles)
        assertEquals(audio(selected = 2), tracks.audio)
        assertEquals(listOf(103L), tracks.activeTrackIds)

        // The server's subtitle pick is a stream the receiver cannot get: none shows.
        val byDefault = castTracks(source, baseUrl, 2, defaultSubtitleTrackId(source))
        assertEquals(subtitles(selected = null), byDefault.subtitles)
        assertEquals(emptyList(), byDefault.activeTrackIds)
    }

    private fun receiverTracks() =
        listOf(
            ReceiverTextTrack(
                id = 103L,
                url = "$baseUrl/Videos/$itemId/$SOURCE_ID/Subtitles/3/0/Stream.vtt?ApiKey=token",
                language = "eng",
                name = "English - SUBRIP - External",
            )
        )

    private fun subtitles(selected: Int?) =
        listOf(
            Track(
                id = 103,
                label = "English - SUBRIP - External",
                language = "eng",
                codec = "subrip",
                selected = selected == 103,
                supported = true,
                isExternal = true,
                isForced = false,
                isHearingImpaired = true,
                isDefault = false,
            ),
            Track(
                id = 104,
                label = "Signs",
                language = "nld",
                codec = "PGSSUB",
                selected = false,
                supported = false,
                isExternal = false,
                isForced = true,
                isHearingImpaired = false,
                isDefault = false,
            ),
            Track(
                id = 105,
                label = "German - SUBRIP - External",
                language = "deu",
                codec = "subrip",
                selected = false,
                supported = false,
                isExternal = true,
                isForced = false,
                isHearingImpaired = false,
                isDefault = false,
            ),
        )

    private fun audio(selected: Int) =
        listOf(
            Track(
                id = 1,
                label = "English 5.1",
                language = "eng",
                codec = "aac",
                selected = selected == 1,
                supported = true,
                isExternal = false,
                isForced = false,
                isHearingImpaired = false,
                isDefault = true,
                channelLayout = "5.1",
            ),
            Track(
                id = 2,
                label = "Dutch - AC3 - Stereo",
                language = "nld",
                codec = "ac3",
                selected = selected == 2,
                supported = true,
                isExternal = false,
                isForced = false,
                isHearingImpaired = false,
                isDefault = false,
                channelLayout = "stereo",
            ),
        )

    private companion object {
        const val SOURCE_ID = "8f0862286a92020d0cb735d734765afe"
    }
}
