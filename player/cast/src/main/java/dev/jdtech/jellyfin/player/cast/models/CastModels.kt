package dev.jdtech.jellyfin.player.cast.models

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.Track

enum class CastConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

/** Whether casting can be offered at all, and if not, why: the device sheet says so. */
enum class CastAvailability {
    AVAILABLE,

    /** A build without the Cast SDK (the `libre` flavor): Cast is not shown anywhere. */
    NOT_IN_BUILD,

    /** Turned off in the settings: Cast is not shown anywhere. */
    DISABLED,

    /** Google Play services is missing or too old for the Cast SDK. */
    NO_PLAY_SERVICES,
}

enum class CastError {
    PLAYBACK_FAILED
}

enum class CastPlaybackStatus {
    IDLE,
    BUFFERING,
    PAUSED,
    PLAYING,
    ENDED,
    ERROR,
}

open class Device(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val supportsH265: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Device) return false
        return id == other.id &&
            name == other.name &&
            enabled == other.enabled &&
            supportsH265 == other.supportsH265
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + enabled.hashCode()
        result = 31 * result + supportsH265.hashCode()
        return result
    }

    override fun toString(): String =
        "Device(id=$id, name=$name, enabled=$enabled, supportsH265=$supportsH265)"
}

data class CastPlayerState(
    val status: CastPlaybackStatus = CastPlaybackStatus.IDLE,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    /**
     * CE (#851): the item the receiver reported [currentPosition] for. Null while a load is on its
     * way, after the item ended, and before the receiver has reported a position since: the
     * position is then not one to act on.
     */
    val positionItemId: ItemId? = null,
    /**
     * CE (#851): [currentPosition] is the target of a seek the app sent, shown at once, and not yet
     * a position the receiver reported.
     */
    val positionFromSeek: Boolean = false,
    /**
     * CE (#851): counts the times an item started on the receiver: each load sent to it (also of
     * the same item, such as an audio track switch) and each item it went on to without one (a
     * restored session, its queue moving on). [loadPositionMs] is where the last one starts.
     */
    val load: Int = 0,
    val loadPositionMs: Long = 0L,
    val volume: Float = 1f,
    val isMuted: Boolean = false,
    val hasNextItem: Boolean = false,
    val hasPreviousItem: Boolean = false,
)

data class CastMediaItem(
    val item: PlayerItem,
    /** The source the receiver plays, as the server adapter made it for a receiver (#767). */
    val source: ItemSource? = null,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
)
