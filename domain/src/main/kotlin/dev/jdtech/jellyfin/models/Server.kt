package dev.jdtech.jellyfin.models

/**
 * A saved server (#899: the app's own model of a row of Room's `servers` table). It has no current
 * user until someone logs in: setup saves the server first.
 */
data class Server(
    val id: ServerId,
    val name: String,
    val currentServerAddressId: ServerAddressId?,
    val currentUserId: UserId?,
    /**
     * CE (#770): the kind of server, as [ServerType.value]. Every server saved before the type was
     * stored is a Jellyfin server. Read it through [serverType].
     */
    val type: String = ServerType.JELLYFIN.value,
)

/** CE (#770): the type of [Server], or null when this version does not know [Server.type]. */
val Server.serverType: ServerType?
    get() = ServerType.fromValue(type)

/** A saved address of a [Server]; the app gives each one a random id. */
data class ServerAddress(val id: ServerAddressId, val serverId: ServerId, val address: String)

/** A saved server with all its addresses and its current user. */
data class ServerWithAddresses(
    val server: Server,
    val addresses: List<ServerAddress>,
    val user: User?,
)

/**
 * CE (#984): the address the server uses, which the server lists show; the first one when none is
 * set as current. Null for a server without addresses.
 */
val ServerWithAddresses.currentAddress: ServerAddress?
    get() =
        addresses.firstOrNull { it.id == server.currentServerAddressId } ?: addresses.firstOrNull()

/** A saved server with its current address and its current user, either of which may be unset. */
data class ServerWithAddressAndUser(
    val server: Server,
    val address: ServerAddress?,
    val user: User?,
)
