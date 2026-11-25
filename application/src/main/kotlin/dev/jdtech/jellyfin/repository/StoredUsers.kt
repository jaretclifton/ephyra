package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.UserId
import kotlinx.coroutines.flow.Flow

/**
 * #967: the saved users of each server and which one is current. A user row is keyed by its
 * [UserId] alone, as the table is: the user belongs to one server ([User.serverId]).
 *
 * Saved in the database (#899): each method is one query. Which server, address and user the client
 * uses is not decided here: callers switch the client themselves, after writing. One of the roles
 * the server store was split into (#967); `RoomServerStore` in `data` implements them all.
 */
interface StoredUsers {
    suspend fun user(id: UserId): User?

    /** The saved users of [serverId]. */
    suspend fun users(serverId: ServerId): List<User>

    fun observeUsersCount(serverId: ServerId): Flow<Int>

    /** The current user of [serverId], or null when it has none. */
    suspend fun currentUser(serverId: ServerId): User?

    /** As [currentUser], again on every change of the rows. */
    fun observeCurrentUser(serverId: ServerId): Flow<User?>

    /** The id of the current user of [serverId], again on every change of the row. */
    fun observeCurrentUserId(serverId: ServerId): Flow<UserId?>

    /** Makes [userId] the current user of [serverId]. */
    suspend fun setCurrentUser(serverId: ServerId, userId: UserId)

    /** Leaves [serverId] without a current user. */
    suspend fun clearCurrentUser(serverId: ServerId)

    /** Saves [user], replacing a saved user with its id (and with that its PIN). */
    suspend fun insertUser(user: User)

    suspend fun deleteUser(id: UserId)

    suspend fun setUserImageTag(id: UserId, tag: String?)
}
