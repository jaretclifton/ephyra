package dev.jdtech.jellyfin.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.domain.ports.MediaHttpClient
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.domain.ports.UserDataSyncPorts
import dev.jdtech.jellyfin.server.jellyfin.JellyfinServerSession
import dev.jdtech.jellyfin.server.jellyfin.JellyfinUserDataSyncPorts
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

@Module
@InstallIn(SingletonComponent::class)
object ApiModule {
    @Singleton
    @Provides
    fun provideJellyfinApi(
        @ApplicationContext application: Context,
        appPreferences: AppPreferences,
        database: ServerDatabaseDao,
    ): JellyfinApi {
        val jellyfinApi =
            JellyfinApi.getInstance(
                context = application,
                requestTimeout = appPreferences.getValue(appPreferences.requestTimeout),
                connectTimeout = appPreferences.getValue(appPreferences.connectTimeout),
                socketTimeout = appPreferences.getValue(appPreferences.socketTimeout),
            )

        // CE (#250): the session comes from the copy in preferences, which are already in memory,
        // so whichever thread first asks for the client (often Main, through HomeViewModel) does
        // not wait for Room to open.
        val serverId = appPreferences.getValue(appPreferences.currentServer)
        val saved = appPreferences.savedApiSession()
        val initial =
            when {
                serverId == null -> null
                saved?.serverId == serverId -> saved
                // No copy for this server yet: the first start after updating to a version that
                // keeps one, or the app was stopped between a server switch and the copy. Read Room
                // once, as every start used to.
                else ->
                    runBlocking {
                        database.getServerWithAddressAndUser(serverId).toApiSession(serverId)
                    }
            }
        initial?.let { jellyfinApi.applySession(it) }

        mirrorApiSession(jellyfinApi, appPreferences, database, initial)

        return jellyfinApi
    }

    /** #748: the base URL and the user id for code outside the adapter. */
    @Singleton
    @Provides
    fun provideServerSession(jellyfinApi: JellyfinApi): ServerSession =
        JellyfinServerSession(jellyfinApi)

    /** #762: the server's user data for any saved user, for the sync. */
    @Singleton
    @Provides
    fun provideUserDataSyncPorts(jellyfinApi: JellyfinApi): UserDataSyncPorts =
        JellyfinUserDataSyncPorts(jellyfinApi)

    /** The API's own client, so the player shares its connection pool (#296, #750). */
    @Provides
    fun provideMediaHttpClient(jellyfinApi: JellyfinApi): MediaHttpClient = MediaHttpClient {
        jellyfinApi.httpClient
    }
}
