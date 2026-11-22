package dev.jdtech.jellyfin.player.cast

import dev.jdtech.jellyfin.player.core.domain.models.Track
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * #767: a queue item a version before #767 loaded keeps the server's whole playback info answer
 * under `playbackInfo`. The values under `resources/legacy/` are what that version wrote for the
 * answers in `data/src/test/resources/playback/` (`json.encodeToString(playbackInfo)` with the SDK,
 * as the old controller did). A restore gets the same tracks and play session the old version would
 * have, and keeps none of the URLs.
 */
class LegacyPlaybackInfoTest {
    private val base = "http://127.0.0.1:8096"

    private fun legacy(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("legacy/$name-playbackInfo.json")) { name }
            .readText()

    private fun Track.summary() =
        "$id $label $language $codec selected=$selected supported=$supported " +
            "external=$isExternal forced=$isForced hi=$isHearingImpaired default=$isDefault " +
            "$channelLayout"

    private fun subtitles(selected: Int?) =
        listOf(
            "103 English - SUBRIP - External eng subrip selected=${selected == 103} " +
                "supported=true external=true forced=false hi=true default=false null",
            "104 Signs nld PGSSUB selected=false supported=false external=false forced=true " +
                "hi=false default=false null",
            // Its URL is on another host: the receiver was never given it.
            "105 German - SUBRIP - External deu subrip selected=false supported=false " +
                "external=true forced=false hi=false default=false null",
        )

    private fun audio(selected: Int) =
        listOf(
            "1 English 5.1 eng aac selected=${selected == 1} supported=true external=false " +
                "forced=false hi=false default=true 5.1",
            "2 Dutch - AC3 - Stereo nld ac3 selected=${selected == 2} supported=true " +
                "external=false forced=false hi=false default=false stereo",
        )

    @Test
    fun `a transcode comes back with its play session and its tracks`() {
        val data = assertNotNull(decodeLegacyPlaybackInfo(legacy("cast-transcode"), base))

        assertEquals("8f0862286a92020d0cb735d734765afe", data.mediaSourceId)
        assertEquals("51c3f794f3da47dcb92c5c42f43f8c88", data.playSessionId)
        assertEquals(true, data.transcoded)
        assertEquals(true, data.source().transcoded)
        // The audio stream from the stream URL, the subtitle the receiver shows.
        val (subtitles, audio) = data.tracks(2, 103)
        assertEquals(subtitles(selected = 103), subtitles.map { it.summary() })
        assertEquals(audio(selected = 2), audio.map { it.summary() })
    }

    @Test
    fun `a file that played as it is comes back with its tracks`() {
        val data = assertNotNull(decodeLegacyPlaybackInfo(legacy("cast-direct"), base))

        assertEquals(false, data.transcoded)
        assertEquals(1, data.defaultAudioStreamIndex)
        // No audio stream in the URL: the source's default plays.
        val (subtitles, audio) = data.tracks(null, null)
        assertEquals(subtitles(selected = null), subtitles.map { it.summary() })
        assertEquals(audio(selected = 1), audio.map { it.summary() })
    }

    @Test
    fun `nothing of its URLs is kept`() {
        val data = assertNotNull(decodeLegacyPlaybackInfo(legacy("cast-transcode"), base))
        val encoded = data.encode()

        assertFalse("token" in encoded, encoded)
        assertFalse("http" in encoded, encoded)
        assertFalse("/Videos" in encoded, encoded)
    }

    @Test
    fun `a subtitle URL under the server's address counts, another host does not`() {
        val value =
            """{"MediaSources":[{"Id":"a","MediaStreams":[""" +
                """{"Type":"Subtitle","Index":1,"DeliveryUrl":"$base/Videos/1.vtt"},""" +
                """{"Type":"Subtitle","Index":2,"DeliveryUrl":"//other.example/2.vtt"},""" +
                """{"Type":"Subtitle","Index":3}]}]}"""
        val data = assertNotNull(decodeLegacyPlaybackInfo(value, "$base/"))

        assertEquals(listOf(true, false, false), data.subtitles.map { it.supported })
    }

    @Test
    fun `what cannot be read is nothing to restore`() {
        assertNull(decodeLegacyPlaybackInfo(null, base))
        assertNull(decodeLegacyPlaybackInfo("", base))
        assertNull(decodeLegacyPlaybackInfo("not json", base))
        assertNull(decodeLegacyPlaybackInfo("""{"MediaSources":"nope"}""", base))
        assertNull(decodeLegacyPlaybackInfo("""{"MediaSources":[]}""", base))
        assertNull(decodeLegacyPlaybackInfo("[1,2,3]", base))
    }
}
