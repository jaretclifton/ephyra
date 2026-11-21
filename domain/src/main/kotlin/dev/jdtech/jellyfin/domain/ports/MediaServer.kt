package dev.jdtech.jellyfin.domain.ports

import dev.jdtech.jellyfin.models.ServerType

/** One server, as the repository sees it (#757). */
interface MediaServer {
    val type: ServerType
    val session: ServerSession
    val catalog: CatalogPort
    val userData: UserDataPort
    val playback: PlaybackPort
}
