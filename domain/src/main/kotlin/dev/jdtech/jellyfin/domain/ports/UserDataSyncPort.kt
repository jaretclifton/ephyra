package dev.jdtech.jellyfin.domain.ports

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.UserId
import java.time.LocalDateTime

/**
 * What the server knows about an item for one user. As the sync compares it with the local row, and
 * as the refresh stores it in the row of a download (#633, #635).
 */
data class ServerUserData(
    val itemId: ItemId,
    val played: Boolean,
    val favorite: Boolean,
    val playbackPositionTicks: Long,
    /**
     * As the server gave it: a local date-time of this device's time zone. Null when the server has
     * none; in [UserDataSyncPort.putUserData], null leaves the server's date as it is.
     */
    val lastPlayedDate: LocalDateTime?,
)

/**
 * User data on the server for one saved user, for the sync of what was written while the server was
 * away (#762). Failures are thrown as `ServerException`.
 */
interface UserDataSyncPort {
    /** The server's rows for [itemIds]; ids the server does not know are absent. */
    suspend fun userDataOf(itemIds: List<ItemId>): Map<ItemId, ServerUserData>

    /** Writes the position, played state, favourite and (when not null) last played date. */
    suspend fun putUserData(data: ServerUserData)

    /** Writes the favourite alone; the position and the played state stay as they are. */
    suspend fun setFavorite(itemId: ItemId, favorite: Boolean)

    /** Ends a play session this device left open, at [positionTicks]. */
    suspend fun reportStopped(itemId: ItemId, positionTicks: Long)

    /**
     * The item this device is reported as playing, or null. Null too when this is not the app's
     * current user: another user's client has no session of its own to end.
     */
    suspend fun itemLeftPlaying(): ItemId?
}

fun interface UserDataSyncPorts {
    /** A port for a saved user, who need not be the current one. */
    fun forUser(baseUrl: String, accessToken: String?, userId: UserId): UserDataSyncPort
}
