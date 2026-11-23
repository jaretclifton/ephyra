package dev.jdtech.jellyfin.setup.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.core.server.ServerConnectors
import dev.jdtech.jellyfin.domain.ports.ActiveServerHolder
import dev.jdtech.jellyfin.repository.ServerStore
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.data.SetupRepositoryImpl
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SetupDataModule {
    @Singleton
    @Provides
    fun provideSetupRepository(
        connectors: ServerConnectors,
        serverStore: ServerStore,
        appPreferences: AppPreferences,
        active: ActiveServerHolder,
    ): SetupRepository {
        return SetupRepositoryImpl(
            connectors = connectors,
            serverStore = serverStore,
            appPreferences = appPreferences,
            active = active,
        )
    }
}
