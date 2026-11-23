package dev.jdtech.jellyfin.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.reachability.ServerReachability
import dev.jdtech.jellyfin.domain.ports.CurrentServerCapabilities
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.KnownServerState
import dev.jdtech.jellyfin.repository.PlaybackEvents
import dev.jdtech.jellyfin.repository.ServerAvailability
import dev.jdtech.jellyfin.repository.StoredLibraryNames
import dev.jdtech.jellyfin.repository.UserDataSyncRequester
import dev.jdtech.jellyfin.work.UserDataSync
import javax.inject.Singleton

/**
 * The repository's surroundings. The repository itself and Room are in `data`'s own modules
 * (`MediaRepositoryModule`, `DatabaseModule`, #899); what they take from `core` is bound here.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Singleton @Provides fun provideDataChangeEvents(): DataChangeEvents = DataChangeEvents()

    @Singleton
    @Provides
    fun providePlaybackEvents(dataChanges: DataChangeEvents): PlaybackEvents =
        PlaybackEvents(dataChanges)

    /**
     * CE (#769): what the server in use can do, for code without a repository (settings), over the
     * MediaServer of the server in use (#770: `ServerModule`).
     */
    @Provides
    fun provideCurrentServerCapabilities(server: MediaServer): CurrentServerCapabilities =
        CurrentServerCapabilities {
            server.capabilities
        }

    /** #899: the repository asks the user data sync to run through this. */
    @Provides
    fun provideUserDataSyncRequester(userDataSync: UserDataSync): UserDataSyncRequester =
        userDataSync

    /** #899: the repository reads whether the server is known to be away through this. */
    @Provides
    fun provideKnownServerState(serverReachability: ServerReachability): KnownServerState =
        serverReachability

    /** CE (#912): the download queue goes on when the server is found back, through this. */
    @Provides
    fun provideServerAvailability(serverReachability: ServerReachability): ServerAvailability =
        serverReachability

    /** CE (#775): the stored libraries get the names the app uses for these kinds. */
    @Provides
    fun provideStoredLibraryNames(application: Application): StoredLibraryNames =
        StoredLibraryNames { type ->
            application.getString(
                if (type == CollectionType.Movies) CoreR.string.movies_label
                else CoreR.string.shows_label
            )
        }
}
