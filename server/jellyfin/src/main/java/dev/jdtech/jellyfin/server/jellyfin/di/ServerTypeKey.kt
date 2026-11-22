package dev.jdtech.jellyfin.server.jellyfin.di

import dagger.MapKey
import dev.jdtech.jellyfin.models.ServerType

/**
 * #770: the key of what an adapter contributes to the app's maps by server type (`Map<ServerType,
 * MediaServer>` and the like). Dagger keys those maps by the [ServerType], not by this annotation,
 * so another adapter can declare its own key of the same shape.
 */
@MapKey annotation class ServerTypeKey(val value: ServerType)
