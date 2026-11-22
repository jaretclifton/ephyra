package dev.jdtech.jellyfin.domain.ports

import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerType

/** One server, as the repository sees it (#757). */
interface MediaServer {
    val type: ServerType

    /** #769: what this server can do; the app does not offer what it cannot. */
    val capabilities: ServerCapabilities

    val session: ServerSession
    val catalog: CatalogPort
    val userData: UserDataPort
    val playback: PlaybackPort
}
