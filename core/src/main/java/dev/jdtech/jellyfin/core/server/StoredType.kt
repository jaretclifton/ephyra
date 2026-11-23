package dev.jdtech.jellyfin.core.server

import dev.jdtech.jellyfin.models.ServerType

/**
 * #770: the adapter of [this] for a server whose stored type (`Server.type`) is [storedType].
 * Without a server, the Jellyfin one: every server was a Jellyfin server before servers had a type.
 * Null when this build has no adapter for [storedType].
 *
 * #899: the type of the server in use is [dev.jdtech.jellyfin.domain.ports.ActiveServer.type].
 */
fun <T> Map<ServerType, T>.forStoredType(storedType: String?): T? =
    if (storedType == null) get(ServerType.JELLYFIN)
    else ServerType.fromValue(storedType)?.let { get(it) }
