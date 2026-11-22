package dev.jdtech.jellyfin.domain.ports

import dev.jdtech.jellyfin.models.UserId
import kotlinx.coroutines.flow.Flow

/** The server and user the app is talking to right now. More members arrive with later tasks. */
interface ServerSession {
    /** Null when no server is set. */
    val baseUrl: String?

    /** Null when nobody is signed in. */
    val userId: UserId?

    /** #763: the current user's token, null when nobody is signed in. */
    val accessToken: String?

    /** #763: tokens the server refused while they were the current one. */
    val unauthorized: Flow<String>

    /** #762: emits when a request of the app's reaches the server again after failed ones. */
    val serverReturned: Flow<Unit>

    /**
     * #763: told how each request of the app goes; the reachability banner follows it. Called on
     * the request's own thread, so it is read and written as a volatile field.
     */
    var requestWatcher: RequestWatcher?

    /** #763: how long [probe] waits at most. */
    val probeTimeoutMs: Long

    /** #763: talks to [baseUrl] as [userId] with [accessToken] from now on. */
    fun use(baseUrl: String?, accessToken: String?, userId: UserId?)

    /**
     * #763: asks the server whether it is there. True when it answered, false when it did not, null
     * when no server is set.
     */
    suspend fun probe(): Boolean?

    /**
     * #763: false when the server says the current token is not valid. Throws a
     * [dev.jdtech.jellyfin.models.ServerException] when it cannot say: no answer, or another error.
     */
    suspend fun isTokenValid(): Boolean

    /**
     * #763: keeps the server's session socket open while collected. Completes at once when the
     * server has none.
     */
    fun keepAlive(): Flow<Nothing>

    /** The URL of a user's profile picture, or null when no server is set. */
    fun userImageUrl(userId: UserId, tag: String): String?

    /** #757: the name the server shows for this device. */
    suspend fun updateDeviceName(name: String)

    /** #757: the current user's next episode autoplay setting, null when the server gave none. */
    suspend fun autoPlayNextEpisode(): Boolean?

    /**
     * #757: the profile picture of the user the token sent is for, as the server has it now. The
     * user comes from the answer, not from [userId]: during a switch the token and the id are
     * updated one after the other.
     */
    suspend fun currentUserImage(): UserImage
}

/** #757: a user and the tag of their profile picture, null when they have none. */
data class UserImage(val userId: UserId, val tag: String?)
