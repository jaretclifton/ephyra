package dev.jdtech.jellyfin.player.cast

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The custom data key versions before #767 wrote: the server's whole playback info answer, as JSON
 * with the server's field names. A receiver session can outlive an app update, so a queue item
 * loaded by such a version may still be playing.
 */
internal const val LEGACY_PLAYBACK_INFO_KEY = "playbackInfo"

/** Only the parts of that answer a restore needs; everything else is ignored. */
@Serializable
private data class LegacyPlaybackInfo(
    @SerialName("MediaSources") val mediaSources: List<LegacyMediaSource>? = null,
    @SerialName("PlaySessionId") val playSessionId: String? = null,
)

@Serializable
private data class LegacyMediaSource(
    @SerialName("Id") val id: String? = null,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("DefaultAudioStreamIndex") val defaultAudioStreamIndex: Int? = null,
    @SerialName("MediaStreams") val mediaStreams: List<LegacyMediaStream>? = null,
)

@Serializable
private data class LegacyMediaStream(
    @SerialName("Index") val index: Int? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("IsHearingImpaired") val isHearingImpaired: Boolean = false,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("ChannelLayout") val channelLayout: String? = null,
    @SerialName("DeliveryUrl") val deliveryUrl: String? = null,
)

private val legacyJson = Json { ignoreUnknownKeys = true }

/**
 * [value], written under [LEGACY_PLAYBACK_INFO_KEY] by a version before #767, as the
 * [CastCustomData] the current version writes: the first source's id, play session, whether it is a
 * transcode, its default audio stream and its tracks as that version built them. Nothing of its
 * URLs is kept. `null` when there is none or it cannot be read.
 *
 * Which subtitles the receiver could get followed from their URL then: a path on the server, or an
 * absolute URL under [baseUrl], the server's address now.
 */
internal fun decodeLegacyPlaybackInfo(value: String?, baseUrl: String?): CastCustomData? {
    if (value.isNullOrEmpty()) return null
    val info =
        runCatching { legacyJson.decodeFromString(LegacyPlaybackInfo.serializer(), value) }
            .getOrNull() ?: return null
    val source = info.mediaSources?.firstOrNull() ?: return null
    val streams = source.mediaStreams.orEmpty().filter { it.index != null }
    return CastCustomData(
        mediaSourceId = source.id.orEmpty(),
        playSessionId = info.playSessionId,
        transcoded = !source.transcodingUrl.isNullOrBlank(),
        defaultAudioStreamIndex = source.defaultAudioStreamIndex,
        subtitles =
            streams
                .filter { it.type == "Subtitle" }
                .map {
                    it.toTrackData(
                        id = it.index!! + SUBTITLE_TRACK_ID_OFFSET,
                        supported = it.deliveryUrl?.let { url -> onServer(url, baseUrl) } == true,
                    )
                },
        audio =
            streams
                .filter { it.type == "Audio" }
                .map { it.toTrackData(id = it.index!!, supported = true, audio = true) },
    )
}

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/** Whether the receiver was given [url]: a path on the server, or a URL under [baseUrl]. */
private fun onServer(url: String, baseUrl: String?): Boolean {
    if (url.isEmpty()) return false
    if (!url.startsWith("//") && !SCHEME.containsMatchIn(url)) return true
    val base = baseUrl?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return false
    return url.startsWith("$base/", ignoreCase = true)
}

private fun LegacyMediaStream.toTrackData(id: Int, supported: Boolean, audio: Boolean = false) =
    CastTrackData(
        id = id,
        label = title ?: displayTitle,
        language = language,
        codec = codec,
        supported = supported,
        isExternal = isExternal,
        isForced = isForced,
        isHearingImpaired = isHearingImpaired,
        isDefault = isDefault,
        channelLayout = channelLayout.takeIf { audio },
    )
