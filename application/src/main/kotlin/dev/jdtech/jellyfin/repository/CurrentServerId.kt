package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.ServerId

/**
 * #918: the id of the server the app has chosen, for `data` and `downloads`, which do not read the
 * app's preferences. `core` implements it over the saved preference, read again on every [get].
 */
fun interface CurrentServerId {
    /** The current server's id, or null when none is chosen. */
    fun get(): ServerId?
}
