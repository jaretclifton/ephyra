package dev.jdtech.jellyfin.core.presentation.session

import androidx.compose.runtime.staticCompositionLocalOf
import dev.jdtech.jellyfin.domain.ports.ServerSession
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

/** No server and nobody signed in, so no profile pictures: the avatars show initials. */
object NoServerSession : ServerSession {
    override val baseUrl: String? = null
    override val userId: UserId? = null
    override val serverReturned: Flow<Unit> = emptyFlow()

    override fun userImageUrl(userId: UserId, tag: String): String? = null
}
