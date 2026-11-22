package dev.jdtech.jellyfin.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.reachability.ServerReachability
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.domain.ports.CatalogPort
import dev.jdtech.jellyfin.domain.ports.CurrentServerCapabilities
import dev.jdtech.jellyfin.domain.ports.DeviceCodecs
import dev.jdtech.jellyfin.domain.ports.DeviceReportSource
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.player.DeviceCodecProbe
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.JellyfinRepositoryImpl
import dev.jdtech.jellyfin.repository.PlaybackEvents
import dev.jdtech.jellyfin.repository.StoredLibraryNames
import dev.jdtech.jellyfin.server.jellyfin.JellyfinCatalog
import dev.jdtech.jellyfin.server.jellyfin.JellyfinDeviceProfiles
import dev.jdtech.jellyfin.server.jellyfin.JellyfinDeviceReportSource
import dev.jdtech.jellyfin.server.jellyfin.JellyfinMediaServer
import dev.jdtech.jellyfin.server.jellyfin.JellyfinPlayback
import dev.jdtech.jellyfin.server.jellyfin.JellyfinUserData
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.work.UserDataSync
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Singleton
    @Provides
    fun provideDeviceCodecs(application: Application): DeviceCodecs = DeviceCodecProbe(application)

    /** CE (#752): the Jellyfin device profile for each playback target. */
    @Singleton
    @Provides
    fun provideJellyfinDeviceProfiles(codecs: DeviceCodecs): JellyfinDeviceProfiles =
        JellyfinDeviceProfiles(codecs)

    @Provides
    fun provideDeviceReportSource(
        application: Application,
        codecs: DeviceCodecs,
        profiles: JellyfinDeviceProfiles,
    ): DeviceReportSource = JellyfinDeviceReportSource(application, codecs, profiles)

    @Singleton @Provides fun provideDataChangeEvents(): DataChangeEvents = DataChangeEvents()

    @Singleton
    @Provides
    fun providePlaybackEvents(dataChanges: DataChangeEvents): PlaybackEvents =
        PlaybackEvents(dataChanges)

    /** CE (#756): the catalog reads of the Jellyfin server, without Room. */
    @Singleton
    @Provides
    fun provideCatalogPort(jellyfinApi: JellyfinApi): CatalogPort = JellyfinCatalog(jellyfinApi)

    /** CE (#757): the Jellyfin server the repository talks to, with its ports. */
    @Singleton
    @Provides
    fun provideMediaServer(
        jellyfinApi: JellyfinApi,
        session: ServerSession,
        catalog: CatalogPort,
        deviceProfiles: JellyfinDeviceProfiles,
    ): MediaServer =
        JellyfinMediaServer(
            session = session,
            catalog = catalog,
            userData = JellyfinUserData(jellyfinApi),
            playback = JellyfinPlayback(jellyfinApi, deviceProfiles),
        )

    /** CE (#769): what the server in use can do, for code without a repository (settings). */
    @Provides
    fun provideCurrentServerCapabilities(server: MediaServer): CurrentServerCapabilities =
        CurrentServerCapabilities {
            server.capabilities
        }

    @Singleton
    @Provides
    fun provideJellyfinRepositoryImpl(
        application: Application,
        server: MediaServer,
        serverDatabase: ServerDatabaseDao,
        appPreferences: AppPreferences,
        userDataSync: UserDataSync,
        serverReachability: ServerReachability,
    ): JellyfinRepositoryImpl {

        return JellyfinRepositoryImpl(
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
    fun provideJellyfinRepository(
        jellyfinRepositoryImpl: JellyfinRepositoryImpl
    ): JellyfinRepository = jellyfinRepositoryImpl
}
