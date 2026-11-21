package dev.jdtech.jellyfin.domain.ports

import dev.jdtech.jellyfin.models.UserId

/** The server and user the app is talking to right now. More members arrive with later tasks. */
interface ServerSession {
    /** Null when no server is set. */
    val baseUrl: String?

    /** Null when nobody is signed in. */
    val userId: UserId?

    /** The URL of a user's profile picture, or null when no server is set. */
    fun userImageUrl(userId: UserId, tag: String): String?
}
