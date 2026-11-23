package dev.jdtech.jellyfin.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.domain.ports.ActiveServerHolder
import dev.jdtech.jellyfin.repository.ServerStore
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Singleton

/**
 * What the server adapter takes from `core`. The adapter builds its client and the ports itself
 * (`JellyfinServerModule` in `server/jellyfin`, #758).
 */
@Module
@InstallIn(SingletonComponent::class)
object ApiModule {
    /**
     * #899: the one [ActiveServerHolder], with the saved server, user and token already on it (from
     * preferences, or else from Room once, [SavedSessionRestorer]). Whatever injects it, the
     * adapter's client and the selector included, gets it signed in (#250, #763), so nothing reads
     * it before the saved session is there.
     */
    @Singleton
    @Provides
    fun provideActiveServerHolder(
        appPreferences: AppPreferences,
        serverStore: ServerStore,
    ): ActiveServerHolder =
        ActiveServerHolder().also { SavedSessionRestorer(appPreferences, serverStore).restore(it) }
}
