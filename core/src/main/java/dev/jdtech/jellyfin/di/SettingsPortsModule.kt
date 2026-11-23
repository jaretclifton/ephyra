package dev.jdtech.jellyfin.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.core.settings.SharedPreferencesResumeThresholdsStore
import dev.jdtech.jellyfin.repository.CurrentServerId
import dev.jdtech.jellyfin.repository.NetworkTimeouts
import dev.jdtech.jellyfin.repository.NextUpMaxDays
import dev.jdtech.jellyfin.repository.ResumeThresholdsStore
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Singleton

/**
 * #918: what `data` and the server adapter read of the app's settings, through ports of
 * `application`, so neither reads [AppPreferences] itself. Each reads the preference again on every
 * call, as they did; the caller decides when to read.
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsPortsModule {

    @Provides
    fun provideCurrentServerId(appPreferences: AppPreferences): CurrentServerId = CurrentServerId {
        appPreferences.currentServerId()
    }

    @Provides
    fun provideNextUpMaxDays(appPreferences: AppPreferences): NextUpMaxDays = NextUpMaxDays {
        appPreferences.getValue(appPreferences.homeNextUpMaxDays)
    }

    @Provides
    fun provideNetworkTimeouts(appPreferences: AppPreferences): NetworkTimeouts =
        object : NetworkTimeouts {
            override fun requestTimeout(): Long =
                appPreferences.getValue(appPreferences.requestTimeout)

            override fun connectTimeout(): Long =
                appPreferences.getValue(appPreferences.connectTimeout)

            override fun socketTimeout(): Long =
                appPreferences.getValue(appPreferences.socketTimeout)
        }

    /** CE (#637): the resume settings of each server, in the app's preferences file. */
    @Singleton
    @Provides
    fun provideResumeThresholdsStore(appPreferences: AppPreferences): ResumeThresholdsStore =
        SharedPreferencesResumeThresholdsStore(appPreferences.sharedPreferences)
}
