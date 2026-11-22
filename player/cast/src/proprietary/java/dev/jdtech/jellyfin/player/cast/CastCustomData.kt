package dev.jdtech.jellyfin.player.cast

import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.player.core.domain.models.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A track as the custom data keeps it; which one plays is worked out again when it is read. */
@Serializable
internal data class CastTrackData(
    val id: Int,
    val label: String? = null,
    val language: String? = null,
    val codec: String? = null,
    val supported: Boolean = false,
    val isExternal: Boolean? = false,
    val isForced: Boolean? = false,
    val isHearingImpaired: Boolean? = false,
    val isDefault: Boolean? = false,
    val channelLayout: String? = null,
)

/**
 * What a queue item tells about its stream, under [KEY] in the receiver's custom data, next to the
 * item id: all [CastPlayerControllerImpl] needs to pick a session up again after the app's process
 * was gone (#767). No URL is kept, so no token either: the receiver has the stream URL itself.
 *
 * A receiver session can outlive an app update, so what is read may have been written by another
 * version: unknown keys are ignored, and what cannot be read is no playback info at all.
 */
@Serializable
internal data class CastCustomData(
    val mediaSourceId: String,
    val playSessionId: String? = null,
    val transcoded: Boolean = false,
    val defaultAudioStreamIndex: Int? = null,
    val subtitles: List<CastTrackData> = emptyList(),
    val audio: List<CastTrackData> = emptyList(),
) {
    /** The source the item plays, as far as the reports and [CastMediaItem] need it. */
    fun source(): FindroidSource =
        FindroidSource(
            id = mediaSourceId,
            name = "",
            type = FindroidSourceType.REMOTE,
            path = "",
            size = 0,
            mediaStreams = emptyList(),
            transcoded = transcoded,
            defaultAudioStreamIndex = defaultAudioStreamIndex,
            playSessionId = playSessionId,
        )

    /**
     * The tracks again, as [castTracks] made them: [audioStreamIndex] plays (`null`: the source's
     * default, else the first), [subtitleTrackId] shows when the receiver could get it.
     */
    fun tracks(audioStreamIndex: Int?, subtitleTrackId: Int?): Pair<List<Track>, List<Track>> {
        val playingAudio = audioStreamIndex ?: defaultAudioStreamIndex ?: audio.firstOrNull()?.id
        return subtitles.map { it.toTrack(it.supported && it.id == subtitleTrackId) } to
            audio.map { it.toTrack(it.id == playingAudio) }
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        /**
         * The custom data key. Not the old `playbackInfo`, which held the server's whole answer.
         */
        const val KEY = "playback"

        private val json = Json { ignoreUnknownKeys = true }

        fun of(source: FindroidSource, tracks: CastTracks) =
            CastCustomData(
                mediaSourceId = source.id,
                playSessionId = source.playSessionId,
                transcoded = source.transcoded,
                defaultAudioStreamIndex = source.defaultAudioStreamIndex,
                subtitles = tracks.subtitles.map { it.toData() },
                audio = tracks.audio.map { it.toData() },
            )

        /** [value] read back, `null` when there is none or it cannot be read. */
        fun decode(value: String?): CastCustomData? =
            value
                ?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

private fun Track.toData() =
    CastTrackData(
        id = id,
        label = label,
        language = language,
        codec = codec,
        supported = supported,
        isExternal = isExternal,
        isForced = isForced,
        isHearingImpaired = isHearingImpaired,
        isDefault = isDefault,
        channelLayout = channelLayout,
    )

private fun CastTrackData.toTrack(selected: Boolean) =
    Track(
        id = id,
        label = label,
        language = language,
        codec = codec,
        selected = selected,
        supported = supported,
        isExternal = isExternal,
        isForced = isForced,
        isHearingImpaired = isHearingImpaired,
        isDefault = isDefault,
        channelLayout = channelLayout,
    )
