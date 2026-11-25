package dev.jdtech.jellyfin.server.jellyfin

import androidx.annotation.VisibleForTesting
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.RequestWatcher
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.domain.ports.UserImage
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.asString
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.PlaystateMessage
import timber.log.Timber

/**
 * [ServerSession] over the Jellyfin client. Reads the client on every access: the address and the
 * user change when the user switches server or profile.
 *
 * #899: each of [baseUrl], [userId] and [accessToken] is one read of the holder's value
 * ([JellyfinApi.snapshot]).
 */
class JellyfinServerSession(private val api: JellyfinApi) : ServerSession {
    override val baseUrl: String?
        get() = api.snapshot().baseUrl

    override val userId: UserId?
        get() = api.snapshot().userId

    override val accessToken: String?
        get() = api.snapshot().accessToken

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

    /**
     * #763: talks to [baseUrl] as [userId] with [accessToken] from now on. #899: for the adapter's
     * tests only, in one write that keeps the server; the app writes the holder (rule R16).
     */
    @VisibleForTesting
    fun use(baseUrl: String?, accessToken: String?, userId: UserId?) {
        api.activeServer.onSameServer {
            it.copy(baseUrl = baseUrl, accessToken = accessToken, userId = userId)
        }
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
     * ends. Play state messages need no start message, so nothing is streamed to us.
     *
     * #899: there is one socket, which reads the holder when it connects. When the address or the
     * token on the holder changes it closes and connects again with the new ones, as the SDK did on
     * `update` before: the server never has two sockets from the device.
     */
    override fun keepAlive(): Flow<Nothing> = flow {
        coroutineScope {
            // UNDISPATCHED: the value of now is taken before the socket connects, so every later
            // change reconnects it. Only the address and the token make a socket; a change of user
            // alone keeps it.
            launch(start = CoroutineStart.UNDISPATCHED) {
                api.activeServer.flow
                    .map { it.baseUrl to it.accessToken }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { api.socketMoved() }
            }
            api.api.webSocket.subscribe<PlaystateMessage>().collect { message ->
                Timber.d("Ignoring play state command %s", message)
            }
        }
    }

    /** The server hands the picture out without authentication. */
    override fun userImageUrl(userId: UserId, tag: String): String? {
        val base = baseUrl?.trimEnd('/') ?: return null
        return "$base/Users/${userId.asString()}/Images/Primary?tag=$tag"
    }

    override suspend fun autoPlayNextEpisode(): Boolean? = call {
        val configuration = api.userApi.getCurrentUser().content.configuration
        if (configuration == null) Timber.w("The server gave no user configuration")
        configuration?.enableNextEpisodeAutoPlay
    }

    override suspend fun currentUserImage(): UserImage = call {
        val user = api.userApi.getCurrentUser().content
        UserImage(user.id.toUserId(), user.primaryImageTag)
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}
