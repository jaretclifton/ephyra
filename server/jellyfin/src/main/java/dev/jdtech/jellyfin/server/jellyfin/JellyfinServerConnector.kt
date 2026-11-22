package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.AuthenticatedUser
import dev.jdtech.jellyfin.domain.ports.PublicUser
import dev.jdtech.jellyfin.domain.ports.ResolvedServer
import dev.jdtech.jellyfin.domain.ports.ServerConnectException
import dev.jdtech.jellyfin.domain.ports.ServerConnector
import dev.jdtech.jellyfin.domain.ports.ServerIssue
import dev.jdtech.jellyfin.models.DiscoveredServer
import dev.jdtech.jellyfin.models.QuickConnectState
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.ServerInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.brandingApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.discovery.RecommendedServerIssue
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.QuickConnectDto
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo

/**
 * #761: [ServerConnector] over the Jellyfin SDK.
 *
 * The calls after [resolve] go through the app's shared client when it is at the address asked for,
 * as they did before #761: it keeps the device id and the detection of a revoked token in one place
 * ([JellyfinApi]). The connector never changes that client.
 */
class JellyfinServerConnector(private val api: JellyfinApi) : ServerConnector {
    /** #769: Jellyfin has every feature the app knows. */
    override val capabilities: ServerCapabilities = ServerCapabilities.ALL

    override fun discover(): Flow<DiscoveredServer> =
        api.jellyfin.discovery.discoverLocalServers().map { it.toDiscoveredServer() }

    /**
     * A GREAT server is taken at once, a GOOD one over an OK one, and an OK one is refused with its
     * issues. CE (#819): runs on [JellyfinApi.unwatchedDiscovery], so a server being added is not
     * counted as traffic of the server in use.
     */
    override suspend fun resolve(address: String): ResolvedServer {
        val discovery = api.unwatchedDiscovery
        val candidates = discovery.getAddressCandidates(address)
        val recommended = discovery.getRecommendedServers(candidates, RecommendedServerInfoScore.OK)
        val goodServers = mutableListOf<RecommendedServerInfo>()
        val okServers = mutableListOf<RecommendedServerInfo>()

        for (recommendedServerInfo in recommended) {
            when (recommendedServerInfo.score) {
                RecommendedServerInfoScore.GREAT -> return recommendedServerInfo.toResolvedServer()
                RecommendedServerInfoScore.GOOD -> goodServers.add(recommendedServerInfo)
                RecommendedServerInfoScore.OK -> okServers.add(recommendedServerInfo)
                RecommendedServerInfoScore.BAD -> Unit
            }
        }

        when {
            goodServers.isNotEmpty() -> return goodServers.first().toResolvedServer()
            okServers.isNotEmpty() ->
                throw ServerConnectException(okServers.first().issues.map { it.toServerIssue() })
            else -> throw ServerConnectException(emptyList())
        }
    }

    /**
     * CE (#760): runs on [JellyfinApi.unwatchedDiscovery], so checking an address says nothing
     * about the server in use and never touches the client's base URL.
     */
    override suspend fun findAddress(address: String, serverId: ServerId): String? {
        val discovery = api.unwatchedDiscovery
        val candidates = discovery.getAddressCandidates(address)
        val recommended = discovery.getRecommendedServers(candidates, RecommendedServerInfoScore.OK)
        return recommended.firstOrNull { it.systemInfo.getOrNull()?.id == serverId }?.address
    }

    override suspend fun loginDisclaimer(address: String): String? =
        call(address) { it.brandingApi.getBrandingOptions().content.loginDisclaimer }

    override suspend fun publicUsers(address: String): List<PublicUser> =
        call(address) { client ->
            client.userApi.getPublicUsers().content.mapNotNull {
                PublicUser(
                    id = it.id.toUserId(),
                    name = it.name ?: return@mapNotNull null,
                    primaryImageTag = it.primaryImageTag,
                )
            }
        }

    override suspend fun login(
        address: String,
        username: String,
        password: String,
    ): AuthenticatedUser =
        call(address) { client ->
            client.authenticationApi
                .authenticateUserByName(
                    data = AuthenticateUserByName(username = username, pw = password)
                )
                .content
                .toAuthenticatedUser()
        }

    override suspend fun quickConnectEnabled(address: String): Boolean =
        call(address) { it.authenticationApi.getQuickConnectEnabled().content }

    override suspend fun startQuickConnect(address: String): QuickConnectState =
        call(address) { it.authenticationApi.initiateQuickConnect().content.toQuickConnectState() }

    override suspend fun quickConnectState(address: String, secret: String): QuickConnectState =
        call(address) { client ->
            client.authenticationApi.getQuickConnectState(secret).content.toQuickConnectState()
        }

    override suspend fun loginWithQuickConnect(address: String, secret: String): AuthenticatedUser =
        call(address) { client ->
            client.authenticationApi
                .authenticateWithQuickConnect(data = QuickConnectDto(secret = secret))
                .content
                .toAuthenticatedUser()
        }

    override suspend fun endSession(baseUrl: String, accessToken: String): Boolean =
        api.endSession(baseUrl = baseUrl, accessToken = accessToken)

    /**
     * Runs [block] on a client for [address]. A failure the SDK can tell (a status, a timeout, TLS)
     * is thrown as a [dev.jdtech.jellyfin.models.ServerException]; anything else as it is, so the
     * screens tell it apart as before.
     */
    private suspend fun <T> call(address: String, block: suspend (ApiClient) -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block(clientFor(address))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw e.asServerException() ?: e
            }
        }

    /**
     * The shared client when it is at [address], which is what the setup repository passes.
     * Otherwise a client of its own, without a token: the shared client's base URL is the session's
     * and stays as it is, and its token never goes to another server.
     */
    private fun clientFor(address: String): ApiClient =
        if (api.api.baseUrl == address) {
            api.api
        } else {
            api.jellyfin.createApi(baseUrl = address, httpClientOptions = api.api.httpClientOptions)
        }
}

internal fun ServerDiscoveryInfo.toDiscoveredServer() =
    DiscoveredServer(id = id, name = name, address = address)

internal fun QuickConnectResult.toQuickConnectState() =
    QuickConnectState(code = code, secret = secret, authenticated = authenticated)

/** A server without an id or a name is refused as [ServerIssue.Kind.NO_ID]. */
internal fun RecommendedServerInfo.toResolvedServer(): ResolvedServer {
    val info = systemInfo.getOrNull()
    val id = info?.id
    val name = info?.serverName
    if (id == null || name == null) {
        throw ServerConnectException(listOf(ServerIssue(ServerIssue.Kind.NO_ID)))
    }
    return ResolvedServer(
        info = ServerInfo(id = id, name = name, version = info.version, product = info.productName),
        address = address,
    )
}

internal fun RecommendedServerIssue.toServerIssue(): ServerIssue =
    when (this) {
        is RecommendedServerIssue.OutdatedServerVersion ->
            ServerIssue(ServerIssue.Kind.OUTDATED_VERSION, version.toString())
        is RecommendedServerIssue.InvalidProductName ->
            ServerIssue(ServerIssue.Kind.NOT_THIS_SERVER_TYPE, productName ?: "")
        is RecommendedServerIssue.UnsupportedServerVersion ->
            ServerIssue(ServerIssue.Kind.UNSUPPORTED_VERSION, version.toString())
        is RecommendedServerIssue.SlowResponse ->
            ServerIssue(ServerIssue.Kind.SLOW_RESPONSE, responseTime.toString())
        else -> ServerIssue(ServerIssue.Kind.UNKNOWN)
    }

/** The `!!` are the repository's from before #761: a result without them fails the login. */
internal fun AuthenticationResult.toAuthenticatedUser() =
    AuthenticatedUser(
        id = user!!.id.toUserId(),
        name = user!!.name!!,
        serverId = serverId!!,
        accessToken = accessToken!!,
        primaryImageTag = user?.primaryImageTag,
    )
