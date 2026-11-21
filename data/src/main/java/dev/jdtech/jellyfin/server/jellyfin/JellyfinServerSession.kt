package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.models.UserId

/**
 * [ServerSession] over the Jellyfin client. Reads the client on every access: the address and the
 * user change when the user switches server or profile.
 */
class JellyfinServerSession(private val api: JellyfinApi) : ServerSession {
    override val baseUrl: String?
        get() = api.api.baseUrl

    override val userId: UserId?
        get() = api.userId

    /** The server hands the picture out without authentication. */
    override fun userImageUrl(userId: UserId, tag: String): String? {
        val base = baseUrl?.trimEnd('/') ?: return null
        return "$base/Users/$userId/Images/Primary?tag=$tag"
    }
}
