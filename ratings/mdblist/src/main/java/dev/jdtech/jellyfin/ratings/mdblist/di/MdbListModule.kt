package dev.jdtech.jellyfin.ratings.mdblist.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.ratings.RatingsCache
import dev.jdtech.jellyfin.ratings.RatingsProvider
import dev.jdtech.jellyfin.ratings.mdblist.FileRatingsCache
import dev.jdtech.jellyfin.ratings.mdblist.MdbListClient
import java.io.File
import javax.inject.Singleton

/**
 * CE (#331): what the MDBList adapter provides. `core` puts the ratings service over these, with
 * the user's settings.
 */
@Module
@InstallIn(SingletonComponent::class)
object MdbListModule {
    @Singleton @Provides fun provideRatingsProvider(): RatingsProvider = MdbListClient()

    @Singleton
    @Provides
    fun provideRatingsCache(@ApplicationContext context: Context): RatingsCache =
        FileRatingsCache(File(context.cacheDir, "external_ratings"))
}
