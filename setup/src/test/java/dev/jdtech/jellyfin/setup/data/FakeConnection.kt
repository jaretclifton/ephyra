package dev.jdtech.jellyfin.setup.data

import dev.jdtech.jellyfin.domain.ports.ActiveServer
import dev.jdtech.jellyfin.domain.ports.ActiveServerHolder
import dev.jdtech.jellyfin.domain.ports.AuthenticatedUser
import dev.jdtech.jellyfin.domain.ports.PublicUser
import dev.jdtech.jellyfin.domain.ports.ResolvedServer
import dev.jdtech.jellyfin.domain.ports.ServerConnector
import dev.jdtech.jellyfin.models.DiscoveredServer
import dev.jdtech.jellyfin.models.QuickConnectState
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.UserId
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * #761: a [ServerConnector] whose calls answer with the `on…` functions; those not set fail.
 * [findAddressCalls] records the addresses checked, [calls] the name and the address of every call
 * after [resolve].
 */
class FakeServerConnector(
    var onResolve: suspend (String) -> ResolvedServer = { error("resolve($it) not expected") },
    var onFindAddress: suspend (String, ServerId) -> String? = { address, _ ->
        error("findAddress($address) not expected")
    },
) : ServerConnector {
    val findAddressCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val calls: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    override var capabilities: ServerCapabilities = ServerCapabilities.ALL

    var onLogin: suspend (String, String) -> AuthenticatedUser = { _, _ -> unexpected() }
    var onPublicUsers: suspend () -> List<PublicUser> = { unexpected() }
    var onEndSession: suspend (String) -> Boolean = { unexpected() }

    override fun discover(): Flow<DiscoveredServer> = emptyFlow()

    override suspend fun resolve(address: String): ResolvedServer = onResolve(address)

    override suspend fun findAddress(address: String, serverId: ServerId): String? {
        findAddressCalls += address
        return onFindAddress(address, serverId)
    }

    override suspend fun loginDisclaimer(address: String): String? = unexpected()

    override suspend fun publicUsers(address: String): List<PublicUser> {
        calls += "publicUsers" to address
        return onPublicUsers()
    }

    override suspend fun login(
        address: String,
        username: String,
        password: String,
    ): AuthenticatedUser {
        calls += "login" to address
        return onLogin(username, password)
    }

    override suspend fun quickConnectEnabled(address: String): Boolean = unexpected()

    override suspend fun startQuickConnect(address: String): QuickConnectState = unexpected()

    override suspend fun quickConnectState(address: String, secret: String): QuickConnectState =
        unexpected()

    override suspend fun loginWithQuickConnect(address: String, secret: String): AuthenticatedUser =
        unexpected()

    override suspend fun endSession(baseUrl: String, accessToken: String): Boolean {
        calls += "endSession" to baseUrl
        return onEndSession(accessToken)
    }

    private fun unexpected(): Nothing = throw UnsupportedOperationException("not expected")
}

/**
 * #761: the client the setup repository puts servers and users on, recording every [use].
 *
 * #899: the client is [active], which the repository is given; every value put there counts as a
 * use, whoever puts it there, and [baseUrl], [accessToken] and [userId] read it.
 */
class RecordingServerSession(
    baseUrl: String? = null,
    accessToken: String? = null,
    userId: UserId? = null,
    serverId: ServerId? = null,
) {
    data class Use(val baseUrl: String?, val accessToken: String?, val userId: UserId?)

    val active =
        ActiveServerHolder(
            ActiveServer(
                serverId = serverId,
                baseUrl = baseUrl,
                accessToken = accessToken,
                userId = userId,
            )
        )

    val uses: MutableList<Use> = Collections.synchronizedList(mutableListOf())

    init {
        // Unconfined: each value is recorded in the write that puts it on, before the write
        // returns.
        CoroutineScope(Dispatchers.Unconfined).launch {
            active.flow.drop(1).collect { uses += Use(it.baseUrl, it.accessToken, it.userId) }
        }
    }

    val baseUrl: String?
        get() = active.value.baseUrl

    val accessToken: String?
        get() = active.value.accessToken

    val userId: UserId?
        get() = active.value.userId

    fun use(baseUrl: String?, accessToken: String?, userId: UserId?) {
        active.onSameServer {
            it.copy(baseUrl = baseUrl, accessToken = accessToken, userId = userId)
        }
    }
}
