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
import dev.jdtech.jellyfin.domain.ports.SessionRestorer
import dev.jdtech.jellyfin.domain.ports.UserDataSyncPorts
import dev.jdtech.jellyfin.server.jellyfin.JellyfinServerSession
import dev.jdtech.jellyfin.server.jellyfin.JellyfinUserDataSyncPorts
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ApiModule {
    @Singleton
    @Provides
    fun provideJellyfinApi(
        @ApplicationContext application: Context,
        appPreferences: AppPreferences,
        sessionRestorer: SessionRestorer,
    ): JellyfinApi {
        val jellyfinApi =
            JellyfinApi.getInstance(
                context = application,
                requestTimeout = appPreferences.getValue(appPreferences.requestTimeout),
                connectTimeout = appPreferences.getValue(appPreferences.connectTimeout),
                socketTimeout = appPreferences.getValue(appPreferences.socketTimeout),
            )

        // CE (#250, #763): signed in before anything uses the client.
        sessionRestorer.restore(JellyfinServerSession(jellyfinApi))

        return jellyfinApi
    }

    /** #763: the saved server, user and token, from preferences or else from Room once. */
    @Provides
    fun provideSessionRestorer(
        appPreferences: AppPreferences,
        database: ServerDatabaseDao,
    ): SessionRestorer = SavedSessionRestorer(appPreferences, database)

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
