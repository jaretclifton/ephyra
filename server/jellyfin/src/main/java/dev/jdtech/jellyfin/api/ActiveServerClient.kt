package dev.jdtech.jellyfin.api

import dev.jdtech.jellyfin.domain.ports.ActiveServer
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo

/**
 * #899: the app's SDK client, without an address or token of its own. Every request, URL and socket
 * reads the [ActiveServer] once, through [snapshot], and goes through an SDK client made for that
 * value's address and token, which never changes ([at]). So a request sends the address and the
 * token of one value, also when a switch comes while it is being built.
 *
 * The SDK client of the last value is kept and reused until the address or the token changes; all
 * of them come from [jellyfin], so they share the connection pool and the response watcher of
 * `JellyfinApi`.
 */
internal class ActiveServerClient(
    private val jellyfin: Jellyfin,
    override val httpClientOptions: HttpClientOptions,
    private val snapshot: () -> ActiveServer,
    private val onSameServer: ((ActiveServer) -> ActiveServer) -> Unit,
) : ApiClient() {
    private class Bound(val baseUrl: String?, val accessToken: String?, val client: ApiClient)

    @Volatile private var last: Bound? = null

    /** The SDK client for [server]'s address and token. */
    fun at(server: ActiveServer): ApiClient {
        val bound = last
        if (
            bound != null &&
                bound.baseUrl == server.baseUrl &&
                bound.accessToken == server.accessToken
        ) {
            return bound.client
        }
        val client =
            jellyfin.createApi(
                baseUrl = server.baseUrl,
                accessToken = server.accessToken,
                httpClientOptions = httpClientOptions,
            )
        last = Bound(server.baseUrl, server.accessToken, client)
        return client
    }

    override val baseUrl: String?
        get() = snapshot().baseUrl

    override val accessToken: String?
        get() = snapshot().accessToken

    override val clientInfo: ClientInfo
        get() = checkNotNull(jellyfin.clientInfo)

    override val deviceInfo: DeviceInfo
        get() = checkNotNull(jellyfin.deviceInfo)

    /** The socket of the server in use now; `keepAlive` follows the holder for later ones. */
    override val webSocket: SocketApi
        get() = at(snapshot()).webSocket

    /**
     * Only the adapter's own tests set the client up this way. The app switches through the holder
     * (`ActiveServerHolder`), and the build fails when production code calls this (rule R16).
     * Address and token change in one write; the client and device info are fixed.
     */
    override fun update(
        baseUrl: String?,
        accessToken: String?,
        clientInfo: ClientInfo,
        deviceInfo: DeviceInfo,
    ) {
        onSameServer { it.copy(baseUrl = baseUrl, accessToken = accessToken) }
    }

    override fun createUrl(
        pathTemplate: String,
        pathParameters: Map<String, Any?>,
        queryParameters: Map<String, Any?>,
        ignorePathParameters: Boolean,
    ): String =
        at(snapshot())
            .createUrl(pathTemplate, pathParameters, queryParameters, ignorePathParameters)

    override suspend fun request(
        method: HttpMethod,
        pathTemplate: String,
        pathParameters: Map<String, Any?>,
        queryParameters: Map<String, Any?>,
        requestBody: Any?,
    ): RawResponse =
        at(snapshot()).request(method, pathTemplate, pathParameters, queryParameters, requestBody)
}
