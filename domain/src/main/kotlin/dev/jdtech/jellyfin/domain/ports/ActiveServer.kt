package dev.jdtech.jellyfin.domain.ports

import dev.jdtech.jellyfin.models.AccountKey
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.UserId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * #899: the server the app talks to and the user it talks as, as one value: which adapter ([type]),
 * where ([baseUrl]), with what ([accessToken]) and for whom ([account]). A request reads it once
 * and uses that one value from start to end, so a switch is never half seen.
 *
 * Every field is nullable, each for a state the app is in today:
 * - [type] null: no server was ever set ([NONE]); the selectors pick Jellyfin then, as before
 *   servers had a type. Otherwise the type as stored (`Server.type`), also one this build has no
 *   adapter for.
 * - [serverId] null: no server, or a client set up without one (the adapter's own tests).
 * - [baseUrl] null: the database has no address for the server.
 * - [accessToken] and [userId] null: nobody is signed in, during the setup or after a sign-out.
 */
data class ActiveServer(
    val type: String? = null,
    val serverId: ServerId? = null,
    val baseUrl: String? = null,
    val accessToken: String? = null,
    val userId: UserId? = null,
) {
    /** The account the requests are made for, null when no server or nobody is signed in. */
    val account: AccountKey?
        get() = if (serverId != null && userId != null) AccountKey(serverId, userId) else null

    /** The token is not printed: this ends up in logs. */
    override fun toString(): String =
        "ActiveServer(type=$type, serverId=${serverId?.value}, baseUrl=$baseUrl, " +
            "accessToken=${if (accessToken == null) "null" else "***"}, userId=${userId?.value})"

    companion object {
        /** Nothing set: the app before the first server, and every start without one. */
        val NONE = ActiveServer()
    }
}

/**
 * #899: holds the [ActiveServer], the only place that does. One instance for the whole app (a
 * singleton in Hilt), read by the server selector (`CurrentMediaServer`), the adapters' HTTP layer,
 * the session keeper and the reachability probe.
 *
 * Each write replaces the whole value at once. [switchTo] puts another server on (adding one,
 * switching to one, the session restore at start); [onSameServer] changes the address, the token or
 * the user of the server in use and cannot change which server that is.
 */
class ActiveServerHolder(initial: ActiveServer = ActiveServer.NONE) {
    private val state = MutableStateFlow(initial)

    /** The value now and every later one. */
    val flow: StateFlow<ActiveServer> = state.asStateFlow()

    /** The value now. Read it once per request and keep that one. */
    val value: ActiveServer
        get() = state.value

    /** Puts [server] on, whatever was on before. */
    fun switchTo(server: ActiveServer) {
        state.value = server
    }

    /**
     * Puts [server] on only while [expected] is still on: false, and nothing changed, when
     * something else was put on meanwhile.
     */
    fun switchTo(server: ActiveServer, expected: ActiveServer): Boolean =
        state.compareAndSet(expected, server)

    /**
     * Changes the address, the token or the user of the server in use, from the value that is on
     * when it applies (retried when another write comes in between). The type and the server id
     * stay what they are: changing those is a switch.
     */
    fun onSameServer(change: (ActiveServer) -> ActiveServer) {
        state.update { current ->
            change(current).copy(type = current.type, serverId = current.serverId)
        }
    }
}
