package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.CatalogPort
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.PlaybackPort
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.domain.ports.UserDataPort
import dev.jdtech.jellyfin.models.ServerType

/** A Jellyfin server (#757): its ports, all over the app's one client. */
class JellyfinMediaServer(
    override val session: ServerSession,
    override val catalog: CatalogPort,
    override val userData: UserDataPort,
    override val playback: PlaybackPort,
) : MediaServer {
    override val type: ServerType = ServerType.JELLYFIN

    /** Every port over [api]. */
    constructor(
        api: JellyfinApi,
        deviceProfiles: JellyfinDeviceProfiles,
    ) : this(
        session = JellyfinServerSession(api),
        catalog = JellyfinCatalog(api),
        userData = JellyfinUserData(api),
        playback = JellyfinPlayback(api, deviceProfiles),
    )
}
