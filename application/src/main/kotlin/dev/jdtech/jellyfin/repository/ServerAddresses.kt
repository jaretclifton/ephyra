package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerAddressId
import dev.jdtech.jellyfin.models.ServerId

/**
 * #967: the addresses of the saved servers, each keyed by its own random id.
 *
 * Saved in the database (#899): each method is one query. Which server, address and user the client
 * uses is not decided here: callers switch the client themselves, after writing. One of the roles
 * the server store was split into (#967); `RoomServerStore` in `data` implements them all.
 */
interface ServerAddresses {
    /** Saves [address], replacing a saved address with its id. */
    suspend fun insertAddress(address: ServerAddress)

    /** The address [id]. Throws when it is not saved. */
    suspend fun address(id: ServerAddressId): ServerAddress

    /** The address the server [serverId] uses, or null when it has none. */
    suspend fun currentAddress(serverId: ServerId): ServerAddress?

    suspend fun deleteAddress(id: ServerAddressId)
}
