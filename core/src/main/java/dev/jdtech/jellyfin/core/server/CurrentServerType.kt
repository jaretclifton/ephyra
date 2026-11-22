package dev.jdtech.jellyfin.core.server

import dev.jdtech.jellyfin.models.ServerType

/**
 * #770: the type of the server whose address the client has, as stored (`Server.type`), for the
 * selectors ([CurrentMediaServer], the setup's [ServerConnectors]). Null while no server is set.
 *
 * Written where a server is put on the client: at start before the first request, by the session
 * restorer (from the copy in preferences, else from Room), and then by the code that switches to
 * another server or adds one (`SetupRepositoryImpl`). A login, a user or an address switch keeps
 * the server, so its type.
 */
class CurrentServerType {
    @Volatile var stored: String? = null
}

/**
 * #770: the adapter of [this] for a server whose stored type is [storedType]. Without a server, the
 * Jellyfin one: every server was a Jellyfin server before servers had a type. Null when this build
 * has no adapter for [storedType].
 */
fun <T> Map<ServerType, T>.forStoredType(storedType: String?): T? =
    if (storedType == null) get(ServerType.JELLYFIN)
    else ServerType.fromValue(storedType)?.let { get(it) }
