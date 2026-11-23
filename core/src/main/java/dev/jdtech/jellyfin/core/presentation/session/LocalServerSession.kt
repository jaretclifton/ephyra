package dev.jdtech.jellyfin.core.presentation.session

import androidx.compose.runtime.staticCompositionLocalOf
import dev.jdtech.jellyfin.domain.ports.RequestWatcher
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.domain.ports.UserImage
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * #748: the server session for composables, provided once in each `MainActivity`. Read its values
 * where they are used, not once in a `remember`: they change when the user switches server or
 * profile.
 *
 * Previews and UI tests that do not provide it get [NoServerSession].
 */
val LocalServerSession = staticCompositionLocalOf<ServerSession> { NoServerSession }

/**
 * No server and nobody signed in, so no profile pictures: the avatars show initials. It keeps no
 * state: [use] and [requestWatcher] are ignored.
 */
object NoServerSession : ServerSession {
    override val baseUrl: String? = null
    override val userId: UserId? = null
    override val accessToken: String? = null
    override val unauthorized: Flow<String> = emptyFlow()
    override val serverReturned: Flow<Unit> = emptyFlow()
    override var requestWatcher: RequestWatcher?
        get() = null
        set(_) {}

    override val probeTimeoutMs: Long = 0

    override suspend fun probe(): Boolean? = null

    /** There is no server to say. */
    override suspend fun isTokenValid(): Boolean = throw ServerException.Unreachable()

    override fun keepAlive(): Flow<Nothing> = emptyFlow()

    override fun userImageUrl(userId: UserId, tag: String): String? = null

    /** There is no server to tell or to ask. */
    override suspend fun updateDeviceName(name: String) = throw ServerException.Unreachable()

    override suspend fun autoPlayNextEpisode(): Boolean? = throw ServerException.Unreachable()

    override suspend fun currentUserImage(): UserImage = throw ServerException.Unreachable()
}
