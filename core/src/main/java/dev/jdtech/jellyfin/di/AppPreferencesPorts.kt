package dev.jdtech.jellyfin.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.core.settings.SharedPreferencesResumeThresholdsStore
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.repository.CurrentServerId
import dev.jdtech.jellyfin.repository.DownloadSettings
import dev.jdtech.jellyfin.repository.NetworkTimeouts
import dev.jdtech.jellyfin.repository.NextUpMaxDays
import dev.jdtech.jellyfin.repository.ResumeThresholdsStore
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

/** #918: [DownloadSettings] over the preferences, each value read from them on every call. */
@Singleton
class AppPreferencesDownloadSettings @Inject constructor(private val preferences: AppPreferences) :
    DownloadSettings {
    override val maxConcurrentDownloads: Int
        get() = preferences.getValue(preferences.maxConcurrentDownloads)

    override val downloadStorageIndex: String?
        get() = preferences.getValue(preferences.downloadStorageIndex)

    override val downloadTranscodeDolbyVision: Boolean
        get() = preferences.getValue(preferences.downloadTranscodeDolbyVision)

    override val downloadOverMobileData: Boolean
        get() = preferences.getValue(preferences.downloadOverMobileData)

    override val downloadWhenRoaming: Boolean
        get() = preferences.getValue(preferences.downloadWhenRoaming)

    override val smartDownloads: Boolean
        get() = preferences.getValue(preferences.smartDownloads)

    override var smartDownloadsSince: Long
        get() = preferences.getValue(preferences.smartDownloadsSince)
        set(value) = preferences.setValue(preferences.smartDownloadsSince, value)

    override val smartDownloadsKeepReady: Int
        get() = preferences.getValue(preferences.smartDownloadsKeepReady)
}

/** #918: [CurrentServerId] from the `currentServer` preference, read on every call. */
@Singleton
class AppPreferencesCurrentServerId @Inject constructor(private val preferences: AppPreferences) :
    CurrentServerId {
    override fun get(): ServerId? = preferences.currentServerId()
}

/** #918: [NextUpMaxDays] from the home screen's Next Up preference, read on every call. */
@Singleton
class AppPreferencesNextUpMaxDays @Inject constructor(private val preferences: AppPreferences) :
    NextUpMaxDays {
    override fun get(): Int = preferences.getValue(preferences.homeNextUpMaxDays)
}

/**
 * #918: [NetworkTimeouts] from the network preferences, read on every call. The Jellyfin adapter
 * reads them once, when it makes its client.
 */
@Singleton
class AppPreferencesNetworkTimeouts @Inject constructor(private val preferences: AppPreferences) :
    NetworkTimeouts {
    override fun requestTimeout(): Long = preferences.getValue(preferences.requestTimeout)

    override fun connectTimeout(): Long = preferences.getValue(preferences.connectTimeout)

    override fun socketTimeout(): Long = preferences.getValue(preferences.socketTimeout)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppPreferencesPortsModule {
    @Binds abstract fun bindDownloadSettings(impl: AppPreferencesDownloadSettings): DownloadSettings

    @Binds abstract fun bindCurrentServerId(impl: AppPreferencesCurrentServerId): CurrentServerId

    @Binds abstract fun bindNextUpMaxDays(impl: AppPreferencesNextUpMaxDays): NextUpMaxDays

    @Binds abstract fun bindNetworkTimeouts(impl: AppPreferencesNetworkTimeouts): NetworkTimeouts

    companion object {
        /**
         * CE (#637), #918: the resume settings of each server, in the preferences' own file under
         * the keys `data` used.
         */
        @Singleton
        @Provides
        fun provideResumeThresholdsStore(preferences: AppPreferences): ResumeThresholdsStore =
            SharedPreferencesResumeThresholdsStore(preferences.sharedPreferences)
    }
}
