package dev.jdtech.jellyfin.repository

import android.app.Application
import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.PlayerEngineId
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.toItemId
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Architecture 0.4 (#738): pins the body of `POST /Items/{id}/PlaybackInfo` the app sends, for
 * playback (getPlaybackSources) and for a download (getMediaSources), so moving the profile
 * decision out of `data` cannot change it unnoticed. Only the fields that carry the decision are
 * compared. The device profile is compared by its shape: under Robolectric MediaCodecList has no
 * decoders, and JellyfinDeviceProfilesTest covers the assembly from probed codecs.
 *
 * No current server is set, so neither call reads a download. The server answers with a recorded
 * answer of the test server (`resources/playback/direct-file.json`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaybackInfoRequestTest {
    private val itemId = "8f086228-6a92-020d-0cb7-35d734765afe".toItemId()
    private val server = StandInServer { request ->
        if (request.path.endsWith("/PlaybackInfo")) StandInServer.resource(ANSWER) else null
    }
    private val context = StandInServer.context()
    private val preferences = StandInServer.preferences(context)
    private val repository by lazy { server.repository(context, preferences = preferences) }

    @After
    fun stop() {
        server.close()
    }

    /**
     * The decision a playback info request carries; `null` for a field not sent or sent null. The
     * app never sets EnableDirectPlay or EnableDirectStream, so every case pins them as not sent.
     */
    private data class Decision(
        val enableDirectPlay: String? = null,
        val enableDirectStream: String? = null,
        val enableTranscoding: String?,
        val allowVideoStreamCopy: String?,
        val allowAudioStreamCopy: String?,
        val maxStreamingBitrate: String?,
        val mediaSourceId: String? = null,
        val audioStreamIndex: String? = null,
        val autoOpenLiveStream: String?,
        val profileName: String?,
        val directPlayProfiles: Int,
        /** `Container/Protocol` of each transcoding profile, in order. */
        val transcodingProfiles: List<String>,
    )

    private fun JsonObject.value(name: String): String? =
        get(name)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    private fun sentDecision(): Decision {
        val request = server.requests.single { it.path.endsWith("/PlaybackInfo") }
        assertEquals(
            "POST /Items/${itemId.asString()}/PlaybackInfo",
            "${request.method} ${request.path}",
        )
        val body = request.json()
        val profile = body.getValue("DeviceProfile").jsonObject
        return Decision(
            enableDirectPlay = body.value("EnableDirectPlay"),
            enableDirectStream = body.value("EnableDirectStream"),
            enableTranscoding = body.value("EnableTranscoding"),
            allowVideoStreamCopy = body.value("AllowVideoStreamCopy"),
            allowAudioStreamCopy = body.value("AllowAudioStreamCopy"),
            maxStreamingBitrate = body.value("MaxStreamingBitrate"),
            mediaSourceId = body.value("MediaSourceId"),
            audioStreamIndex = body.value("AudioStreamIndex"),
            autoOpenLiveStream = body.value("AutoOpenLiveStream"),
            profileName = profile.value("Name"),
            directPlayProfiles = profile.getValue("DirectPlayProfiles").jsonArray.size,
            transcodingProfiles =
                profile.getValue("TranscodingProfiles").jsonArray.map {
                    "${it.jsonObject.value("Container")}/${it.jsonObject.value("Protocol")}"
                },
        )
    }

    // CE (#766): the player decides the target and the cap; the defaults are its defaults.
    private var engine = PlayerEngineId.EXOPLAYER
    private var maxBitrate: Int? = null

    private fun mpv() {
        engine = PlayerEngineId.MPV
    }

    private fun cap(bitrate: Int) {
        maxBitrate = bitrate
    }

    /** Asks for the item's playback sources as the local player does. */
    private suspend fun play(mediaSourceId: String? = null, audioStreamIndex: Int? = null) =
        repository.getPlaybackSources(
            itemId,
            PlaybackTarget.LocalPlayer(engine, directPlayDolbyVision = true),
            mediaSourceId = mediaSourceId,
            audioStreamIndex = audioStreamIndex,
            maxBitrate = maxBitrate,
        )

    // ExoPlayer: the probed profile, which carries the HLS and audio transcoding profiles.
    private val exoPlayer =
        Decision(
            enableTranscoding = "true",
            allowVideoStreamCopy = "true",
            allowAudioStreamCopy = "true",
            maxStreamingBitrate = "120000000",
            autoOpenLiveStream = "true",
            profileName = "Ephyra Device Profile",
            directPlayProfiles = EXO_DIRECT_PLAY_PROFILES,
            transcodingProfiles = listOf("ts/hls", "mp3/http"),
        )

    @Test
    fun `ExoPlayer sends the probed profile`() = runBlocking {
        play()

        assertEquals(exoPlayer, sentDecision())
    }

    @Test
    fun `ExoPlayer with a cap sends the cap as the maximum bitrate`() = runBlocking {
        cap(CAP)

        play()

        assertEquals(exoPlayer.copy(maxStreamingBitrate = "$CAP"), sentDecision())
    }

    @Test
    fun `mpv sends the direct play profile without transcoding`() = runBlocking {
        mpv()

        play()

        assertEquals(
            Decision(
                enableTranscoding = "false",
                allowVideoStreamCopy = "true",
                allowAudioStreamCopy = "true",
                maxStreamingBitrate = "1000000000",
                autoOpenLiveStream = "false",
                profileName = "Direct play all",
                directPlayProfiles = 2,
                transcodingProfiles = emptyList(),
            ),
            sentDecision(),
        )
    }

    @Test
    fun `mpv with a cap sends the direct play profile with the HLS fallback`() = runBlocking {
        mpv()
        cap(CAP)

        play()

        assertEquals(
            Decision(
                enableTranscoding = "true",
                allowVideoStreamCopy = "true",
                allowAudioStreamCopy = "true",
                maxStreamingBitrate = "$CAP",
                autoOpenLiveStream = "true",
                profileName = "Direct play all",
                directPlayProfiles = 2,
                transcodingProfiles = listOf("ts/hls", "mp3/http"),
            ),
            sentDecision(),
        )
    }

    @Test
    fun `an audio stream is sent together with its source`() = runBlocking {
        play(mediaSourceId = SOURCE_ID, audioStreamIndex = 2)

        assertEquals(
            exoPlayer.copy(mediaSourceId = SOURCE_ID, audioStreamIndex = "2"),
            sentDecision(),
        )
    }

    @Test
    fun `an audio stream without its source is not sent`() = runBlocking {
        play(mediaSourceId = null, audioStreamIndex = 2)

        assertEquals(exoPlayer, sentDecision(), message = "the server ignores the index alone")
    }

    @Test
    fun `a source without an audio stream is not sent`() = runBlocking {
        play(mediaSourceId = SOURCE_ID, audioStreamIndex = null)

        assertEquals(exoPlayer, sentDecision(), message = "sent only as a pair")
    }

    // A download never lets the server copy the video: a transcode is a real encode.
    private val download =
        Decision(
            enableTranscoding = "false",
            allowVideoStreamCopy = "false",
            allowAudioStreamCopy = "true",
            maxStreamingBitrate = "1000000000",
            autoOpenLiveStream = null,
            profileName = "Direct play all",
            directPlayProfiles = 2,
            transcodingProfiles = emptyList(),
        )

    @Test
    fun `a download of the original sends the direct play profile`() = runBlocking {
        repository.getMediaSources(itemId, includePath = true)

        assertEquals(download, sentDecision())
    }

    @Test
    fun `a download with Dolby Vision transcode sends the download profile`() = runBlocking {
        repository.getMediaSources(itemId, includePath = true, transcodeDolbyVision = true)

        assertEquals(
            download.copy(
                enableTranscoding = "true",
                profileName = "Ephyra Download Profile",
                transcodingProfiles = listOf("ts/http"),
            ),
            sentDecision(),
        )
    }

    @Test
    fun `a download with a cap sends the cap and a progressive transcode`() = runBlocking {
        repository.getMediaSources(itemId, includePath = true, maxBitrate = CAP)

        assertEquals(
            // The profile keeps the direct play profile's name, with the download transcode added.
            download.copy(
                enableTranscoding = "true",
                maxStreamingBitrate = "$CAP",
                transcodingProfiles = listOf("ts/http"),
            ),
            sentDecision(),
        )
    }

    private companion object {
        const val ANSWER = "playback/direct-file.json"
        const val SOURCE_ID = "8f0862286a92020d0cb735d734765afe"
        const val CAP = 8_000_000

        /**
         * The direct play profiles of the probed profile when MediaCodecList has no decoders, as
         * under Robolectric: one per supported container.
         */
        const val EXO_DIRECT_PLAY_PROFILES = 8
    }
}
