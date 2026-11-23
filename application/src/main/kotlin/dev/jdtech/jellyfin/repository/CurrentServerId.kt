package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.ServerId

/**
 * The server the app is signed in to, or none. Read live on every call, as the callers do not keep
 * it. The app provides the implementation (#918): a port keeps the download engine and the stores
 * off the UI's preferences.
 */
fun interface CurrentServerId {
    fun get(): ServerId?
}
