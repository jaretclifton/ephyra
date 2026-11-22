package dev.jdtech.jellyfin.domain.ports

import dev.jdtech.jellyfin.models.DiscoveredServer
import dev.jdtech.jellyfin.models.QuickConnectState
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.ServerInfo
import dev.jdtech.jellyfin.models.UserId
import kotlinx.coroutines.flow.Flow

/** #761: why the server at an address cannot be used. [detail] is what the message shows. */
data class ServerIssue(val kind: Kind, val detail: String? = null) {
    enum class Kind {
        /** [detail] is the server's version. */
        OUTDATED_VERSION,

        /** [detail] is the server's version. */
        UNSUPPORTED_VERSION,

        /** Another kind of server answered; [detail] is the product name it gave, or empty. */
        NOT_THIS_SERVER_TYPE,

        /** [detail] is the response time in milliseconds. */
        SLOW_RESPONSE,

        /** The server answered without an id or a name. */
        NO_ID,

        /** Any other issue. */
        UNKNOWN,
    }
}

/** #761: no usable server at the address. [issues] is empty when nothing answered. */
class ServerConnectException(val issues: List<ServerIssue>) : Exception()

/** #761: the server found for an address, and the address it was found at. */
data class ResolvedServer(val info: ServerInfo, val address: String)

/** #761: a user the server signed in. */
data class AuthenticatedUser(
    val id: UserId,
    val name: String,
    val serverId: ServerId,
    val accessToken: String,
    val primaryImageTag: String?,
)

/** #761: a user the server lists on its login screen. */
data class PublicUser(val id: UserId, val name: String, val primaryImageTag: String?)

/**
 * #761: everything that happens before there is a session: finding a server, logging in. One
 * implementation per server type. Failures to talk to the server are thrown as
 * [dev.jdtech.jellyfin.models.ServerException] where the implementation can tell what they are.
 */
interface ServerConnector {
    /**
     * #769: what servers of this type can do. The login screens need
     * [ServerCapabilities.quickConnect], [ServerCapabilities.publicUsers] and
     * [ServerCapabilities.loginDisclaimer] before there is a session.
     */
    val capabilities: ServerCapabilities

    /** The servers that answer the search on the local network. */
    fun discover(): Flow<DiscoveredServer>

    /** The best of the candidate addresses for [address]. Throws [ServerConnectException]. */
    suspend fun resolve(address: String): ResolvedServer

    /**
     * The address among the candidates for [address] at which the server [serverId] answers, or
     * null when it does not answer there. Unlike [resolve] it is not a request of the server in
     * use: the [RequestWatcher] does not see it (#760).
     */
    suspend fun findAddress(address: String, serverId: ServerId): String?

    // Every call below names the address it is for: a connector holds no "current server".

    suspend fun loginDisclaimer(address: String): String?

    suspend fun publicUsers(address: String): List<PublicUser>

    suspend fun login(address: String, username: String, password: String): AuthenticatedUser

    suspend fun quickConnectEnabled(address: String): Boolean

    suspend fun startQuickConnect(address: String): QuickConnectState

    suspend fun quickConnectState(address: String, secret: String): QuickConnectState

    suspend fun loginWithQuickConnect(address: String, secret: String): AuthenticatedUser

    /** Signs [accessToken] out. Best effort; false when the server could not be reached in time. */
    suspend fun endSession(baseUrl: String, accessToken: String): Boolean
}
