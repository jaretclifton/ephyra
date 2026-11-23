package dev.jdtech.jellyfin.di

import android.app.Application
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.repository.DownloadStore
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.utils.DownloaderImpl
import dev.jdtech.jellyfin.utils.download.MediaDownloadEngine
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DownloaderModule {
    @Singleton
    @Provides
    fun provideDownloader(
        application: Application,
        downloadStore: DownloadStore,
        mediaRepositoryProvider: Provider<MediaRepository>,
        appPreferences: AppPreferences,
        workManagerProvider: Provider<WorkManager>,
        engine: MediaDownloadEngine,
        dataChanges: DataChangeEvents,
    ): Downloader {
        return DownloaderImpl(
            application,
            downloadStore,
            mediaRepositoryProvider,
            appPreferences,
            workManagerProvider,
            engine,
            dataChanges,
        )
    }
}
