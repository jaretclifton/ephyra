package dev.jdtech.jellyfin.player.cast

import dev.jdtech.jellyfin.models.FindroidMediaStream
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.MediaStreamType
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.player.cast.models.CastMediaItem
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.Track
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [castTracks]: the tracks the receiver and the track dialogs get for a source the
 * server adapter made for a receiver (#626, #767). The source here has a video stream, English and
 * Dutch audio, and three subtitles: one the receiver can fetch (the adapter gave it a URL), one the
 * server burns in or leaves in the stream, and one whose URL pointed away from the server (both
 * without a URL).
 */
class CastTracksTest {

    private val base = "https://jellyfin.example"

    private val source =
        source(
            stream(0, MediaStreamType.VIDEO),
            stream(1, MediaStreamType.AUDIO, language = "eng", title = "English 5.1"),
            stream(2, MediaStreamType.AUDIO, language = "nld", displayTitle = "Dutch - AAC"),
            stream(3, MediaStreamType.SUBTITLE, language = "eng", path = "$base/Videos/1/3.vtt"),
            stream(4, MediaStreamType.SUBTITLE, language = "nld", title = "Signs"),
            stream(5, MediaStreamType.SUBTITLE, language = "deu"),
        )

    @Test
    fun `subtitle track ids are the stream index plus 100, audio track ids the stream index`() {
        val tracks = castTracks(source, audioStreamIndex = null, subtitleTrackId = null)

        assertEquals(listOf(103, 104, 105), tracks.subtitles.map { it.id })
        assertEquals(listOf(1, 2), tracks.audio.map { it.id })
        assertEquals(listOf("eng", "nld", "deu"), tracks.subtitles.map { it.language })
        assertEquals(listOf("English 5.1", "Dutch - AAC"), tracks.audio.map { it.label })
    }

    @Test
    fun `a subtitle is supported only when the receiver has a URL for it`() {
        val tracks = castTracks(source, audioStreamIndex = null, subtitleTrackId = null)

        assertEquals(listOf(true, false, false), tracks.subtitles.map { it.supported })
        assertEquals(
            listOf(
                ReceiverTextTrack(
                    id = 103L,
                    url = "$base/Videos/1/3.vtt",
                    language = "eng",
                    name = null,
                )
            ),
            tracks.receiverTracks,
        )
        assertTrue(tracks.audio.all { it.supported })
    }

    @Test
    fun `the chosen audio stream is the one that plays`() {
        val tracks = castTracks(source, audioStreamIndex = 2, subtitleTrackId = null)

        assertEquals(listOf(false, true), tracks.audio.map { it.selected })
    }

    @Test
    fun `without a choice the source's default audio stream plays`() {
        val tracks =
            castTracks(
                source.copy(defaultAudioStreamIndex = 2),
                audioStreamIndex = null,
                subtitleTrackId = null,
            )

        assertEquals(listOf(false, true), tracks.audio.map { it.selected })
    }

    @Test
    fun `without a choice or a default the first audio stream plays`() {
        val tracks = castTracks(source, audioStreamIndex = null, subtitleTrackId = null)

        assertEquals(listOf(true, false), tracks.audio.map { it.selected })
    }

    @Test
    fun `the subtitle that shows is selected and active`() {
        val tracks = castTracks(source, audioStreamIndex = null, subtitleTrackId = 103)

        assertEquals(listOf(true, false, false), tracks.subtitles.map { it.selected })
        assertEquals(listOf(103L), tracks.activeTrackIds)
    }

    @Test
    fun `no subtitle shows without a subtitle track id`() {
        val tracks = castTracks(source, audioStreamIndex = null, subtitleTrackId = null)

        assertTrue(tracks.subtitles.none { it.selected })
        assertEquals(emptyList<Long>(), tracks.activeTrackIds)
    }

    @Test
    fun `a subtitle the receiver cannot get is neither selected nor active`() {
        // The receiver was not told about these tracks; naming one would fail the whole load.
        listOf(104, 105).forEach { trackId ->
            val tracks = castTracks(source, audioStreamIndex = null, subtitleTrackId = trackId)

            assertTrue(tracks.subtitles.none { it.selected })
            assertEquals(emptyList<Long>(), tracks.activeTrackIds)
        }
    }

    @Test
    fun `the server's subtitle pick becomes a track id`() {
        assertEquals(103, defaultSubtitleTrackId(source.copy(defaultSubtitleStreamIndex = 3)))
        assertNull(defaultSubtitleTrackId(source.copy(defaultSubtitleStreamIndex = -1)))
        assertNull(defaultSubtitleTrackId(source))
    }

    @Test
    fun `a track is named by its title, else by the server's display title`() {
        val tracks = castTracks(source, audioStreamIndex = null, subtitleTrackId = null)

        assertEquals("Signs", tracks.subtitles[1].label)
        assertEquals("Dutch - AAC", tracks.audio[1].label)
        assertNull(tracks.subtitles[0].label)
        // The mapper keeps a missing title as "": that is no title either.
        val titled =
            castTracks(
                source(stream(1, MediaStreamType.AUDIO, title = "", displayTitle = "AAC - Mono")),
                audioStreamIndex = null,
                subtitleTrackId = null,
            )
        assertEquals("AAC - Mono", titled.audio.single().label)
    }

    @Test
    fun `a missing language or codec is none, not an empty one`() {
        val tracks =
            castTracks(
                source(stream(1, MediaStreamType.AUDIO, codec = "")),
                audioStreamIndex = null,
                subtitleTrackId = null,
            )

        assertNull(tracks.audio.single().language)
        assertNull(tracks.audio.single().codec)
    }

    @Test
    fun `a source without streams has no tracks`() {
        val tracks = castTracks(source(), audioStreamIndex = null, subtitleTrackId = null)

        assertTrue(tracks.subtitles.isEmpty())
        assertTrue(tracks.audio.isEmpty())
        assertTrue(tracks.receiverTracks.isEmpty())
    }

    @Test
    fun `picking a subtitle selects that one alone`() {
        val tracks = castTracks(source, audioStreamIndex = null, subtitleTrackId = 103)
        val item = castItem(tracks)

        assertEquals(
            listOf(false, false, false),
            item.withSubtitle(emptyList()).subtitleTracks.map { it.selected },
        )
        assertEquals(
            listOf(false, true, false),
            item.withSubtitle(listOf(104L)).subtitleTracks.map { it.selected },
        )
        // The audio tracks are left as they were.
        assertEquals(tracks.audio, item.withSubtitle(listOf(104L)).audioTracks)
    }

    @Test
    fun `picking an audio track selects that one alone`() {
        val tracks = castTracks(source, audioStreamIndex = 1, subtitleTrackId = 103)
        val item = castItem(tracks).withAudio(2)

        assertEquals(listOf(false, true), item.audioTracks.map { it.selected })
        assertEquals(tracks.subtitles, item.subtitleTracks)
    }

    // #767: the tracks the receiver got before the move, for the sources the server adapter now
    // makes of the same answers (CastPlaybackRequestTest in data pins those sources).

    private val pinnedBase = "http://127.0.0.1:8096"
    private val pinnedItem = "8f086228-6a92-020d-0cb7-35d734765afe"
    private val pinnedSource = "8f0862286a92020d0cb735d734765afe"

    private fun pinned(defaultSubtitle: Int) =
        source(
                stream(0, MediaStreamType.VIDEO, codec = "h264", displayTitle = "1080p H264 SDR"),
                stream(
                    1,
                    MediaStreamType.AUDIO,
                    language = "eng",
                    title = "English 5.1",
                    displayTitle = "English 5.1 - AAC - 5.1 - Default",
                    codec = "aac",
                    channelLayout = "5.1",
                    isDefault = true,
                ),
                stream(
                    2,
                    MediaStreamType.AUDIO,
                    language = "nld",
                    displayTitle = "Dutch - AC3 - Stereo",
                    codec = "ac3",
                    channelLayout = "stereo",
                ),
                stream(
                    3,
                    MediaStreamType.SUBTITLE,
                    language = "eng",
                    displayTitle = "English - SUBRIP - External",
                    codec = "subrip",
                    isExternal = true,
                    isHearingImpaired = true,
                    path =
                        "$pinnedBase/Videos/$pinnedItem/$pinnedSource/Subtitles/3/0/Stream.vtt" +
                            "?ApiKey=token",
                ),
                stream(
                    4,
                    MediaStreamType.SUBTITLE,
                    language = "nld",
                    title = "Signs",
                    displayTitle = "Signs - Dutch - PGSSUB",
                    codec = "PGSSUB",
                    isForced = true,
                ),
                stream(
                    5,
                    MediaStreamType.SUBTITLE,
                    language = "deu",
                    displayTitle = "German - SUBRIP - External",
                    codec = "subrip",
                    isExternal = true,
                ),
            )
            .copy(defaultAudioStreamIndex = 1, defaultSubtitleStreamIndex = defaultSubtitle)

    private val pinnedReceiverTracks =
        listOf(
            ReceiverTextTrack(
                id = 103L,
                url =
                    "$pinnedBase/Videos/$pinnedItem/$pinnedSource/Subtitles/3/0/Stream.vtt" +
                        "?ApiKey=token",
                language = "eng",
                name = "English - SUBRIP - External",
            )
        )

    private fun pinnedSubtitles(selected: Int?) =
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

    private fun pinnedAudio(selected: Int) =
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

    @Test
    fun `pinned - a new item on a file the receiver plays as it is`() {
        val source = pinned(defaultSubtitle = 3)
        assertEquals(103, defaultSubtitleTrackId(source))

        // As buildMedia for a new item: no audio choice, the server's subtitle.
        val tracks = castTracks(source, null, defaultSubtitleTrackId(source))

        assertEquals(pinnedReceiverTracks, tracks.receiverTracks)
        assertEquals(pinnedSubtitles(selected = 103), tracks.subtitles)
        assertEquals(pinnedAudio(selected = 1), tracks.audio)
        assertEquals(listOf(103L), tracks.activeTrackIds)
    }

    @Test
    fun `pinned - an audio switch to a transcode`() {
        val source = pinned(defaultSubtitle = 4)
        assertEquals(104, defaultSubtitleTrackId(source))

        // As buildMedia for an audio switch: the chosen audio, the subtitle that showed.
        val tracks = castTracks(source, 2, 103)
        assertEquals(pinnedReceiverTracks, tracks.receiverTracks)
        assertEquals(pinnedSubtitles(selected = 103), tracks.subtitles)
        assertEquals(pinnedAudio(selected = 2), tracks.audio)
        assertEquals(listOf(103L), tracks.activeTrackIds)

        // The server's subtitle pick is a stream the receiver cannot get: none shows.
        val byDefault = castTracks(source, 2, defaultSubtitleTrackId(source))
        assertEquals(pinnedSubtitles(selected = null), byDefault.subtitles)
        assertEquals(emptyList(), byDefault.activeTrackIds)
    }

    private fun castItem(tracks: CastTracks) =
        CastMediaItem(
            item =
                PlayerItem(
                    name = "Movie",
                    itemId = UUID.randomUUID().toString().toItemId(),
                    mediaSourceId = "source",
                    playbackPosition = 0L,
                ),
            subtitleTracks = tracks.subtitles,
            audioTracks = tracks.audio,
        )

    /** A stream as the mapper makes it: no title or language is "", a codec is always there. */
    private fun stream(
        index: Int,
        type: MediaStreamType,
        language: String = "",
        title: String = "",
        displayTitle: String? = null,
        codec: String = "codec",
        channelLayout: String? = null,
        isExternal: Boolean = false,
        isForced: Boolean = false,
        isHearingImpaired: Boolean = false,
        isDefault: Boolean = false,
        path: String? = null,
    ) =
        FindroidMediaStream(
            title = title,
            displayTitle = displayTitle,
            language = language,
            type = type,
            codec = codec,
            profile = null,
            isExternal = isExternal,
            path = path,
            channelLayout = channelLayout,
            videoRangeType = null,
            height = null,
            width = null,
            videoDoViTitle = null,
            index = index,
            isForced = isForced,
            isDefault = isDefault,
            isHearingImpaired = isHearingImpaired,
        )

    private fun source(vararg streams: FindroidMediaStream) =
        FindroidSource(
            id = "source",
            name = "",
            type = FindroidSourceType.REMOTE,
            path = "",
            size = 0,
            mediaStreams = streams.toList(),
        )
}
