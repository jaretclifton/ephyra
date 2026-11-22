package dev.jdtech.jellyfin.models

data class FindroidSource(
    val id: MediaSourceId,
    val name: String,
    val type: FindroidSourceType,
    val path: String,
    val size: Long,
    val mediaStreams: List<FindroidMediaStream>,
    val downloadId: Long? = null,
    /**
     * True when [path] points at a server-side transcode rather than the original file: an HLS
     * manifest on the playback path, or a progressive stream on the download path (Dolby Vision
     * conversion or a user-picked quality cap).
     */
    val transcoded: Boolean = false,
    /** CE (#510): for the media info sheet. A downloaded source has neither. */
    val container: String? = null,
    val bitrate: Int? = null,
    /** CE (#580): the audio stream the server plays, and puts in a transcode, for this source. */
    val defaultAudioStreamIndex: Int? = null,
    /**
     * CE (#728): the play session of a transcode on the playback path, which the playback reports
     * name once the player loads it. Null for anything else.
     */
    val playSessionId: String? = null,
    /** CE (#767): the subtitle stream the server picks for this user, -1 or null for none. */
    val defaultSubtitleStreamIndex: Int? = null,
)

enum class FindroidSourceType {
    REMOTE,
    LOCAL,
}
