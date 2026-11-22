package dev.jdtech.jellyfin.core.server

import dev.jdtech.jellyfin.domain.ports.CatalogPort
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.PlaybackPort
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.domain.ports.UserDataPort
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerType

/**
 * #770: the [MediaServer] of the server in use. Delegates every port to the adapter for that
 * server's type ([currentType]), looked up on each access: a call takes its port once, when it
 * starts, so a request started on one server finishes on that server's adapter after a switch.
 *
 * A server of a type this build has no adapter for gets [UnsupportedMediaServer]: no features, no
 * session, and every request fails with
 * [dev.jdtech.jellyfin.domain.ports.UnsupportedServerTypeException].
 */
class CurrentMediaServer(
    private val servers: Map<ServerType, MediaServer>,
    private val currentType: CurrentServerType,
) : MediaServer {
    private fun current(): MediaServer {
        val stored = currentType.stored
        return servers.forStoredType(stored)
            ?: UnsupportedMediaServer(stored ?: ServerType.JELLYFIN.value)
    }

    override val type: ServerType
        get() = current().type

    override val capabilities: ServerCapabilities
        get() = current().capabilities

    override val session: ServerSession
        get() = current().session

    override val catalog: CatalogPort
        get() = current().catalog

    override val userData: UserDataPort
        get() = current().userData

    override val playback: PlaybackPort
        get() = current().playback
}
