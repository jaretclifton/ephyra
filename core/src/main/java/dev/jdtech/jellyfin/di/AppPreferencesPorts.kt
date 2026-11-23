package dev.jdtech.jellyfin.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.repository.CurrentServerId
import dev.jdtech.jellyfin.repository.DownloadSettings
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

@Module
@InstallIn(SingletonComponent::class)
abstract class AppPreferencesPortsModule {
    @Binds abstract fun bindDownloadSettings(impl: AppPreferencesDownloadSettings): DownloadSettings

    @Binds abstract fun bindCurrentServerId(impl: AppPreferencesCurrentServerId): CurrentServerId
}
