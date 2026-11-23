package dev.jdtech.jellyfin.models

data class MediaStream(
    val title: String,
    val displayTitle: String?,
    val language: String,
    val type: MediaStreamType,
    val codec: String,
    val profile: String?,
    val isExternal: Boolean,
    val path: String?,
    val channelLayout: String?,
    val videoRangeType: VideoRangeType?,
    val height: Int?,
    val width: Int?,
    val videoDoViTitle: String?,
    // CE (#510): what the media info sheet shows. Not stored with a download, so a local
    // stream has none of it.
    val bitRate: Int? = null,
    val frameRate: Float? = null,
    val bitDepth: Int? = null,
    val sampleRate: Int? = null,
    /** CE (#580): the server's number for the stream, which a playback request names. */
    val index: Int? = null,
    /** CE (#596): the channel count of an audio stream, for its name in the audio dialog. */
    val channels: Int? = null,
    /**
     * CE (#597): how the server delivers a subtitle stream for the source it is listed with.
     * [SubtitleDeliveryMethod.EXTERNAL] with a [path] is a file of its own, also for a track that
     * is inside the video file: the server extracts it.
     */
    val deliveryMethod: SubtitleDeliveryMethod? = null,
    val isForced: Boolean = false,
    val isDefault: Boolean = false,
    /** CE (#767): what the Cast track dialogs show. */
    val isHearingImpaired: Boolean = false,
)
