package dev.jdtech.jellyfin.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.core.server.CurrentServerType
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.domain.ports.SessionRestorer
import dev.jdtech.jellyfin.settings.domain.AppPreferences

/**
 * What the server adapter takes from `core`. The adapter builds its client and the ports itself
 * (`JellyfinServerModule` in `server/jellyfin`, #758).
 */
@Module
@InstallIn(SingletonComponent::class)
object ApiModule {
    /**
     * #763: the saved server, user and token, from preferences or else from Room once. CE (#770):
     * and the server's type, for the selectors.
     */
    @Provides
    fun provideSessionRestorer(
        appPreferences: AppPreferences,
        database: ServerDatabaseDao,
        currentType: CurrentServerType,
    ): SessionRestorer = SavedSessionRestorer(appPreferences, database, currentType)
}
