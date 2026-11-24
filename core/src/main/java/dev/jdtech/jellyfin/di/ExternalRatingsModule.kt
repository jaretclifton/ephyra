package dev.jdtech.jellyfin.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.ratings.ExternalRatings
import dev.jdtech.jellyfin.ratings.ExternalRatingsService
import dev.jdtech.jellyfin.ratings.ExternalRatingsSettings
import dev.jdtech.jellyfin.ratings.RatingsCache
import dev.jdtech.jellyfin.ratings.RatingsProvider
import javax.inject.Singleton

/**
 * CE (#331): the external ratings of the movie and show pages. The service holds the back-offs and
 * the rejected key in memory, so there is one. The provider and the cache come from the MDBList
 * adapter (`ratings/mdblist`), which only the apps depend on.
 */
@Module
@InstallIn(SingletonComponent::class)
object ExternalRatingsModule {
    @Singleton
    @Provides
    fun provideExternalRatings(
        settings: ExternalRatingsSettings,
        provider: RatingsProvider,
        cache: RatingsCache,
    ): ExternalRatings = ExternalRatingsService(settings, provider, cache)
}
