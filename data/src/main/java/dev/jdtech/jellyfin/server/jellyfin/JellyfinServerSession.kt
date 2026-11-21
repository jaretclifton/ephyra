package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.RequestWatcher
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.DeviceOptionsDto
import org.jellyfin.sdk.model.api.PlaystateMessage
import timber.log.Timber

/**
 * [ServerSession] over the Jellyfin client. Reads the client on every access: the address and the
 * user change when the user switches server or profile.
 */
class JellyfinServerSession(private val api: JellyfinApi) : ServerSession {
    override val baseUrl: String?
        get() = api.api.baseUrl

    override val userId: UserId?
        get() = api.userId

    override val accessToken: String?
        get() = api.api.accessToken

    override val unauthorized: Flow<String>
        get() = api.unauthorized

    override val serverReturned: Flow<Unit>
        get() = api.serverReturned

    /** The client's own field, which is volatile: OkHttp's threads read it. */
    override var requestWatcher: RequestWatcher?
        get() = api.requestWatcher
        set(value) {
            api.requestWatcher = value
        }

    override val probeTimeoutMs: Long
        get() = JellyfinApi.PROBE_TIMEOUT_MS

    override fun use(baseUrl: String?, accessToken: String?, userId: UserId?) {
        api.api.update(baseUrl = baseUrl, accessToken = accessToken)
        api.userId = userId
    }

    override suspend fun probe(): Boolean? = api.probeServer()

    /** `/Users/Me`: only a 401 to it says the token is gone. */
    override suspend fun isTokenValid(): Boolean =
        try {
            serverCall { api.userApi.getCurrentUser() }
            true
        } catch (e: ServerException.HttpStatus) {
            if (e.status == HTTP_UNAUTHORIZED) false else throw e
        }

    /**
     * Any subscription keeps the socket open; the SDK closes it a few seconds after the last one
     * ends, and reconnects with the new token or address on a switch. Play state messages need no
     * start message, so nothing is streamed to us.
     */
    override fun keepAlive(): Flow<Nothing> = flow {
        api.api.webSocket.subscribe<PlaystateMessage>().collect { message ->
            Timber.d("Ignoring play state command %s", message)
        }
    }

    /** The server hands the picture out without authentication. */
    override fun userImageUrl(userId: UserId, tag: String): String? {
        val base = baseUrl?.trimEnd('/') ?: return null
        return "$base/Users/$userId/Images/Primary?tag=$tag"
    }

    /** Nothing is sent when the client has no device id. */
    override suspend fun updateDeviceName(name: String) {
        call {
            api.jellyfin.deviceInfo?.id?.let { id ->
                api.deviceApi.updateDeviceOptions(id, DeviceOptionsDto(0, customName = name))
            }
        }
    }

    override suspend fun autoPlayNextEpisode(): Boolean? = call {
        val configuration = api.userApi.getCurrentUser().content.configuration
        if (configuration == null) Timber.w("The server gave no user configuration")
        configuration?.enableNextEpisodeAutoPlay
    }

    override suspend fun currentUserImageTag(): String? = call {
        api.userApi.getCurrentUser().content.primaryImageTag
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}
