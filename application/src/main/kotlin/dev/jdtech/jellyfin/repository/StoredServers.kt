package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddressId
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.ServerWithAddressAndUser
import dev.jdtech.jellyfin.models.ServerWithAddresses
import kotlinx.coroutines.flow.Flow

/**
 * #967: the saved servers, with their addresses and current user as one read. Servers are keyed by
 * [ServerId]. A server without a current user is a valid state: setup saves the server before
 * anyone logs in.
 *
 * Saved in the database (#899): each method is one query. Which server, address and user the client
 * uses is not decided here: callers switch the client themselves, after writing. One of the roles
 * the server store was split into (#967); `RoomServerStore` in `data` implements them all.
 */
interface StoredServers {
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

    /**
     * #988: renames the server [id]; nothing when it is not saved. Only the name: a server's
     * columns change one at a time, so a write to another column in between is kept.
     */
    suspend fun renameServer(id: ServerId, name: String)

    /** #988: makes [addressId] the current address of [serverId]; nothing when it is not saved. */
    suspend fun setCurrentAddress(serverId: ServerId, addressId: ServerAddressId)

    /** Deletes the server, and with it its addresses and users. */
    suspend fun deleteServer(id: ServerId)

    /** Whether Room has any movie or show downloaded from [serverId]. */
    suspend fun hasStoredMoviesOrShows(serverId: ServerId): Boolean
}
