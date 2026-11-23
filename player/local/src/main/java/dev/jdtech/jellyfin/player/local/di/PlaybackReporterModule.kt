package dev.jdtech.jellyfin.player.local.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.player.local.domain.PlaybackReporter
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.repository.PlaybackEvents
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** A [CoroutineScope] that lives as long as the application and is never cancelled. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope

/** CE (#765): the [PlaybackReporter] of each player, and the scope its reports outlive it in. */
@Module
@InstallIn(SingletonComponent::class)
object PlaybackReporterModule {
    /**
     * In place of `GlobalScope`: no parent, never cancelled, and a failure of one coroutine does
     * not cancel the others.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob())

    /** Not scoped: each player gets its own, with the state of its own reports. */
    @Provides
    fun providePlaybackReporter(
        repository: MediaRepository,
        playbackEvents: PlaybackEvents,
        @ApplicationScope reportScope: CoroutineScope,
    ): PlaybackReporter = PlaybackReporter(repository, playbackEvents, reportScope, Dispatchers.IO)
}
