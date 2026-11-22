package dev.jdtech.jellyfin.setup.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.core.server.CurrentServerType
import dev.jdtech.jellyfin.core.server.ServerConnectors
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.domain.ports.ServerSession
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
        session: ServerSession,
        serverDatabase: ServerDatabaseDao,
        appPreferences: AppPreferences,
        currentType: CurrentServerType,
    ): SetupRepository {
        return SetupRepositoryImpl(
            connectors = connectors,
            session = session,
            database = serverDatabase,
            appPreferences = appPreferences,
            currentType = currentType,
        )
    }
}
