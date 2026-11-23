package dev.jdtech.jellyfin.data.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.domain.ports.DeviceCodecs
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.player.DeviceCodecProbe
import dev.jdtech.jellyfin.repository.KnownServerState
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.repository.MediaRepositoryImpl
import dev.jdtech.jellyfin.repository.StoredLibraryNames
import dev.jdtech.jellyfin.repository.UserDataSyncRequester
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Singleton

/**
 * The repository over the server in use and Room (#899: moved from `core`'s `RepositoryModule`).
 * What it takes from `core` comes through the ports `core` binds: [UserDataSyncRequester] (the user
 * data sync), [KnownServerState] (the server reachability) and [StoredLibraryNames] (the app's
 * strings).
 */
@Module
@InstallIn(SingletonComponent::class)
object MediaRepositoryModule {

    @Singleton
    @Provides
    fun provideDeviceCodecs(application: Application): DeviceCodecs = DeviceCodecProbe(application)

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
        syncRequester: UserDataSyncRequester,
        knownServerState: KnownServerState,
        storedLibraryNames: StoredLibraryNames,
    ): MediaRepositoryImpl {
        return MediaRepositoryImpl(
            application,
            server,
            serverDatabase,
            appPreferences,
            syncRequester,
            knownServerState,
            storedLibraryNames,
        )
    }

    /** CE (#281): one repository; downloaded items fall back to Room when the server is away. */
    @Provides
    fun provideMediaRepository(mediaRepositoryImpl: MediaRepositoryImpl): MediaRepository =
        mediaRepositoryImpl
}
