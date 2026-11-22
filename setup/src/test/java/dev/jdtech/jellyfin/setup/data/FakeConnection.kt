package dev.jdtech.jellyfin.setup.data

import dev.jdtech.jellyfin.domain.ports.AuthenticatedUser
import dev.jdtech.jellyfin.domain.ports.PublicUser
import dev.jdtech.jellyfin.domain.ports.RequestWatcher
import dev.jdtech.jellyfin.domain.ports.ResolvedServer
import dev.jdtech.jellyfin.domain.ports.ServerConnector
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.models.DiscoveredServer
import dev.jdtech.jellyfin.models.QuickConnectState
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.UserId
import java.util.Collections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

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

/** #761: a [ServerSession] that holds what [use] puts on it and records every [use]. */
class RecordingServerSession(
    override var baseUrl: String? = null,
    override var accessToken: String? = null,
    override var userId: UserId? = null,
) : ServerSession {
    data class Use(val baseUrl: String?, val accessToken: String?, val userId: UserId?)

    val uses: MutableList<Use> = Collections.synchronizedList(mutableListOf())

    override val unauthorized: Flow<String> = emptyFlow()
    override val serverReturned: Flow<Unit> = emptyFlow()
    override var requestWatcher: RequestWatcher? = null
    override val probeTimeoutMs: Long = 3_000L

    override fun use(baseUrl: String?, accessToken: String?, userId: UserId?) {
        this.baseUrl = baseUrl
        this.accessToken = accessToken
        this.userId = userId
        uses += Use(baseUrl, accessToken, userId)
    }

    override suspend fun probe(): Boolean? = baseUrl?.let { true }

    override suspend fun isTokenValid(): Boolean = true

    override fun keepAlive(): Flow<Nothing> = emptyFlow()

    override fun userImageUrl(userId: UserId, tag: String): String? = null
}
