package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.ServerWithAddressAndUser
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.UserId
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * The saved servers, their addresses and their users (#899, part C1): what setup, the session and
 * the screens read and write about them. Servers are keyed by [ServerId] and addresses by their own
 * random id. A user row is keyed by its [UserId] alone, as the table is: the user belongs to one
 * server ([User.serverId]). A server without a current user is a valid state: setup saves the
 * server before anyone logs in.
 *
 * Each method is one query of the database behind it. Which server, address and user the client
 * uses is not decided here: callers switch the client themselves, after writing.
 */
interface ServerStore {
    // ---- Servers -------------------------------------------------------------------------------

    suspend fun server(id: ServerId): Server?

    fun observeServer(id: ServerId): Flow<Server?>

    /** Every saved server with its addresses and its current user. */
    suspend fun serversWithAddresses(): List<ServerWithAddresses>

    /** The server [id] with its addresses and its current user. Throws when it is not saved. */
    suspend fun serverWithAddresses(id: ServerId): ServerWithAddresses

    /** The server [id] with its current address and current user, or null when it is not saved. */
    suspend fun serverWithAddressAndUser(id: ServerId): ServerWithAddressAndUser?

    /** As [serverWithAddressAndUser], again on every change of the rows. */
    fun observeServerWithAddressAndUser(id: ServerId): Flow<ServerWithAddressAndUser?>

    fun observeServersCount(): Flow<Int>

    /** Saves [server], replacing a saved server with its id. */
    suspend fun insertServer(server: Server)

    /** Writes every column of [server] to its row. */
    suspend fun updateServer(server: Server)

    /** Deletes the server, and with it its addresses and users. */
    suspend fun deleteServer(id: ServerId)

    /** Whether Room has any movie or show downloaded from [serverId]. */
    suspend fun hasStoredMoviesOrShows(serverId: ServerId): Boolean

    // ---- Addresses -----------------------------------------------------------------------------

    /** Saves [address], replacing a saved address with its id. */
    suspend fun insertAddress(address: ServerAddress)

    /** The address [id]. Throws when it is not saved. */
    suspend fun address(id: UUID): ServerAddress

    /** The address the server [serverId] uses, or null when it has none. */
    suspend fun currentAddress(serverId: ServerId): ServerAddress?

    suspend fun deleteAddress(id: UUID)

    // ---- Users ---------------------------------------------------------------------------------

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

    /**
     * CE (#299): sets the PIN hash, or removes the lock with nulls; the lock type becomes `pin`.
     */
    suspend fun setUserPin(id: UserId, hash: String?, salt: String?)

    /** CE (#330): sets a pattern's hash; the lock type becomes `pattern`. */
    suspend fun setUserPattern(id: UserId, hash: String, salt: String)

    suspend fun setUserPinOnStart(id: UserId, pinOnStart: Boolean)
}
