package dev.jdtech.jellyfin.domain.ports

import dev.jdtech.jellyfin.models.UserId
import kotlinx.coroutines.flow.Flow

/** The server and user the app is talking to right now. More members arrive with later tasks. */
interface ServerSession {
    /** Null when no server is set. */
    val baseUrl: String?

    /** Null when nobody is signed in. */
    val userId: UserId?

    /** #762: emits when a request of the app's reaches the server again after failed ones. */
    val serverReturned: Flow<Unit>

    /** The URL of a user's profile picture, or null when no server is set. */
    fun userImageUrl(userId: UserId, tag: String): String?
}
