package dev.jdtech.jellyfin.domain.ports

import dev.jdtech.jellyfin.models.ServerCapabilities

/**
 * #769: the [ServerCapabilities] of the server in use, for code that has no repository to ask. They
 * change with the server, so read them when a screen loads, not once per process.
 */
fun interface CurrentServerCapabilities {
    fun get(): ServerCapabilities
}
