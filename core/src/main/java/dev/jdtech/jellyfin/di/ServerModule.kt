package dev.jdtech.jellyfin.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.core.server.CurrentMediaServer
import dev.jdtech.jellyfin.core.server.ServerConnectors
import dev.jdtech.jellyfin.domain.ports.ActiveServerHolder
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.ServerConnector
import dev.jdtech.jellyfin.models.ServerType
import javax.inject.Singleton

/**
 * #770: the selectors between the app and the server adapters. Each adapter puts its [MediaServer]
 * and [ServerConnector] into a map by its [ServerType] (`JellyfinServerModule`); the rest of the
 * app injects the [MediaServer] and the [ServerConnectors] from here.
 */
@Module
@InstallIn(SingletonComponent::class)
object ServerModule {
    /**
     * #899: the holder comes with the saved session already on it ([ApiModule]), so the type is
     * there before the first request.
     */
    @Singleton
    @Provides
    fun provideMediaServer(
        servers: Map<ServerType, @JvmSuppressWildcards MediaServer>,
        active: ActiveServerHolder,
    ): MediaServer = CurrentMediaServer(servers, active)

    @Singleton
    @Provides
    fun provideServerConnectors(
        connectors: Map<ServerType, @JvmSuppressWildcards ServerConnector>
    ): ServerConnectors = ServerConnectors(connectors)
}
