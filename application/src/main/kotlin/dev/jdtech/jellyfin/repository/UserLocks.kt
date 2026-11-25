package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.UserId

/**
 * #967: the profile lock of a saved user (CE, #299 and #330): a PIN or a pattern, kept as a hash,
 * and whether to ask for it at start. `ProfileLock` in `application` decides with it.
 *
 * Saved in the database (#899): each method is one query. Which server, address and user the client
 * uses is not decided here: callers switch the client themselves, after writing. One of the roles
 * the server store was split into (#967); `RoomServerStore` in `data` implements them all.
 */
interface UserLocks {
    /**
     * CE (#299): sets the PIN hash, or removes the lock with nulls; the lock type becomes `pin`.
     */
    suspend fun setUserPin(id: UserId, hash: String?, salt: String?)

    /** CE (#330): sets a pattern's hash; the lock type becomes `pattern`. */
    suspend fun setUserPattern(id: UserId, hash: String, salt: String)

    suspend fun setUserPinOnStart(id: UserId, pinOnStart: Boolean)
}
