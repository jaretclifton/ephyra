package dev.jdtech.jellyfin.setup.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.domain.ports.ServerConnector
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
        connector: ServerConnector,
        session: ServerSession,
        serverDatabase: ServerDatabaseDao,
        appPreferences: AppPreferences,
    ): SetupRepository {
        return SetupRepositoryImpl(
            connector = connector,
            session = session,
            database = serverDatabase,
            appPreferences = appPreferences,
        )
    }
}
