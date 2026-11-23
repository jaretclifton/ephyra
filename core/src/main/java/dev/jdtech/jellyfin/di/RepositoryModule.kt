package dev.jdtech.jellyfin.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.reachability.ServerReachability
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.domain.ports.CurrentServerCapabilities
import dev.jdtech.jellyfin.domain.ports.DeviceCodecs
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.player.DeviceCodecProbe
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.repository.MediaRepositoryImpl
import dev.jdtech.jellyfin.repository.PlaybackEvents
import dev.jdtech.jellyfin.repository.StoredLibraryNames
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.work.UserDataSync
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Singleton
    @Provides
    fun provideDeviceCodecs(application: Application): DeviceCodecs = DeviceCodecProbe(application)

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

    /**
     * The repository over the server in use: the adapter of its type (#770: `ServerModule`, over
     * what `JellyfinServerModule` in `server/jellyfin` provides, #758).
     */
    @Singleton
    @Provides
    fun provideMediaRepositoryImpl(
        application: Application,
        server: MediaServer,
        serverDatabase: ServerDatabaseDao,
        appPreferences: AppPreferences,
        userDataSync: UserDataSync,
        serverReachability: ServerReachability,
    ): MediaRepositoryImpl {

        return MediaRepositoryImpl(
            application,
            server,
            serverDatabase,
            appPreferences,
            userDataSync,
            serverReachability,
            // CE (#775): the stored libraries get the names the app uses for these kinds.
            StoredLibraryNames { type ->
                application.getString(
                    if (type == CollectionType.Movies) CoreR.string.movies_label
                    else CoreR.string.shows_label
                )
            },
        )
    }

    /** CE (#281): one repository; downloaded items fall back to Room when the server is away. */
    @Provides
    fun provideMediaRepository(mediaRepositoryImpl: MediaRepositoryImpl): MediaRepository =
        mediaRepositoryImpl
}
