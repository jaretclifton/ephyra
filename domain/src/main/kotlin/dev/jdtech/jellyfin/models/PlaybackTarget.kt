package dev.jdtech.jellyfin.models

/** The local player engines. [preferenceValue] is what the player backend preference stores. */
enum class PlayerEngineId(val preferenceValue: String) {
    EXOPLAYER("exoplayer"),
    MPV("mpv");

    companion object {
        /** The engine whose [preferenceValue] is exactly [value], or null for anything else. */
        fun fromPreference(value: String): PlayerEngineId? = entries.firstOrNull {
            it.preferenceValue == value
        }
    }
}

/** Who plays the stream. A server adapter turns this into whatever its server needs (#752). */
sealed interface PlaybackTarget {
    /**
     * A player on this device. [directPlayDolbyVision] is whether the user lets Dolby Vision
     * direct-play where the decoders and the display allow it.
     */
    data class LocalPlayer(val engine: PlayerEngineId, val directPlayDolbyVision: Boolean) :
        PlaybackTarget

    /** A download. [transcodeDolbyVision] routes Dolby Vision through a transcode. */
    data class Download(val transcodeDolbyVision: Boolean) : PlaybackTarget

    /** A Cast receiver; [supportsHevc] is whether it decodes HEVC. */
    data class CastReceiver(val supportsHevc: Boolean) : PlaybackTarget
}
