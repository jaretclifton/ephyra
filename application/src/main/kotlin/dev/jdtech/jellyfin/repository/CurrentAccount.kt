package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.ServerInfo
import dev.jdtech.jellyfin.models.UserId

/**
 * #967: the server and the user in use, as the screens ask about them. One of the roles the
 * repository was split into; what the server can do is `CurrentServerCapabilities` in `domain`.
 */
interface CurrentAccount {
    suspend fun getServerInfo(): ServerInfo

    /** The signed-in user. Throws when no user is signed in. */
    fun getUserId(): UserId

    /** Stores the current user's profile picture tag, as the server has it now. */
    suspend fun refreshCurrentUserImage()

    /**
     * The user's server-side "play next episode automatically" setting, or null when the server has
     * none.
     */
    suspend fun autoPlayNextEpisode(): Boolean?
}
