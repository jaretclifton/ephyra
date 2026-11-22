package dev.jdtech.jellyfin.player.cast

import dev.jdtech.jellyfin.models.FindroidMediaStream
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.MediaStreamType
import dev.jdtech.jellyfin.player.cast.models.CastMediaItem
import dev.jdtech.jellyfin.player.core.domain.models.Track

/** Keeps subtitle track ids apart from the audio stream indices the dialogs also use. */
internal const val SUBTITLE_TRACK_ID_OFFSET = 100

/** A subtitle file the receiver is told about. */
internal data class ReceiverTextTrack(
    val id: Long,
    val url: String,
    val language: String?,
    val name: String?,
)

internal data class CastTracks(
    val receiverTracks: List<ReceiverTextTrack>,
    val subtitles: List<Track>,
    val audio: List<Track>,
) {
    /** The tracks the receiver shows from the start. */
    val activeTrackIds: List<Long>
        get() = subtitles.filter { it.selected && it.supported }.map { it.id.toLong() }
}

/** The id of the subtitle track the server picks for this user, `null` when it picks none. */
internal fun defaultSubtitleTrackId(source: FindroidSource): Int? =
    source.defaultSubtitleStreamIndex?.takeIf { it >= 0 }?.let { it + SUBTITLE_TRACK_ID_OFFSET }

/** The stream's own title, else the one the server makes up for it. */
private val FindroidMediaStream.label: String?
    get() = title.ifEmpty { displayTitle }

/**
 * The tracks of [source], a source the server adapter made for a Cast receiver (its subtitle paths
 * are URLs the receiver may fetch, or null), for the receiver and for the track dialogs.
 * [audioStreamIndex] is the audio stream that plays (`null`: the source's default),
 * [subtitleTrackId] the subtitle track that shows (`null`: none). Subtitle track ids are the stream
 * index plus [SUBTITLE_TRACK_ID_OFFSET]; the receiver gets only those with a URL.
 */
internal fun castTracks(
    source: FindroidSource,
    audioStreamIndex: Int?,
    subtitleTrackId: Int?,
): CastTracks {
    val receiverTracks = mutableListOf<ReceiverTextTrack>()
    val subtitles = mutableListOf<Track>()
    val audio = mutableListOf<Track>()

    val streams = source.mediaStreams
    val playingAudioIndex =
        audioStreamIndex
            ?: source.defaultAudioStreamIndex
            ?: streams.firstOrNull { it.type == MediaStreamType.AUDIO }?.index

    streams.forEach { stream ->
        // The server numbers every stream; one without a number cannot be asked for.
        val index = stream.index ?: return@forEach
        // The mapper keeps a missing language or codec as "".
        val language = stream.language.ifEmpty { null }
        val codec = stream.codec.ifEmpty { null }
        if (stream.type == MediaStreamType.SUBTITLE) {
            val trackId = index + SUBTITLE_TRACK_ID_OFFSET
            val trackUrl = stream.path

            if (trackUrl != null) {
                receiverTracks.add(
                    ReceiverTextTrack(
                        id = trackId.toLong(),
                        url = trackUrl,
                        language = language,
                        name = stream.label,
                    )
                )
            }

            subtitles.add(
                Track(
                    id = trackId,
                    label = stream.label,
                    language = language,
                    codec = codec,
                    selected = trackUrl != null && trackId == subtitleTrackId,
                    supported = trackUrl != null,
                    isExternal = stream.isExternal,
                    isForced = stream.isForced,
                    isHearingImpaired = stream.isHearingImpaired,
                    isDefault = stream.isDefault,
                )
            )
        } else if (stream.type == MediaStreamType.AUDIO) {
            audio.add(
                Track(
                    id = index,
                    label = stream.label,
                    language = language,
                    codec = codec,
                    selected = index == playingAudioIndex,
                    supported = true,
                    isExternal = stream.isExternal,
                    isForced = stream.isForced,
                    isHearingImpaired = stream.isHearingImpaired,
                    isDefault = stream.isDefault,
                    channelLayout = stream.channelLayout,
                )
            )
        }
    }

    return CastTracks(receiverTracks, subtitles, audio)
}

/** This item with [trackIds] as the subtitle tracks that show. */
internal fun CastMediaItem.withSubtitle(trackIds: Collection<Long>): CastMediaItem =
    copy(subtitleTracks = subtitleTracks.map { it.copy(selected = it.id.toLong() in trackIds) })

/** This item with the audio track [trackId] as the one that plays. */
internal fun CastMediaItem.withAudio(trackId: Int): CastMediaItem =
    copy(audioTracks = audioTracks.map { it.copy(selected = it.id == trackId) })
