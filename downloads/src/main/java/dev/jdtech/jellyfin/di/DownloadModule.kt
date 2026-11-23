package dev.jdtech.jellyfin.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.utils.download.ResumeValidatorStore
import dev.jdtech.jellyfin.utils.download.SharedPreferencesResumeValidatorStore
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * Hilt qualifier for the OkHttpClient used exclusively by [MediaDownloadEngine]. Differs from the
 * Jellyfin API client: callTimeout=0 (downloads are long-running), longer read-timeout so a 60 s
 * stall surfaces as IOException rather than hanging forever.
 */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DownloadHttpClient

@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {

    @Singleton
    @Provides
    @DownloadHttpClient
    fun provideDownloadOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .callTimeout(0, TimeUnit.MILLISECONDS) // no overall cap — downloads are long
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    /** CE (#850): the If-Range validators of partial downloads, kept across restarts. */
    @Singleton
    @Provides
    fun provideResumeValidatorStore(@ApplicationContext context: Context): ResumeValidatorStore =
        SharedPreferencesResumeValidatorStore(
            context.getSharedPreferences("download_resume_validators", Context.MODE_PRIVATE)
        )
}
