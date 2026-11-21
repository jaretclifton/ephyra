package dev.jdtech.jellyfin.player.local.engine.di

import dagger.Binds
import dagger.MapKey
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dev.jdtech.jellyfin.models.PlayerEngineId
import dev.jdtech.jellyfin.player.local.engine.PlayerEngine
import dev.jdtech.jellyfin.player.local.engine.exo.ExoEngine
import dev.jdtech.jellyfin.player.local.engine.mpv.MpvEngine

/** Keys a [PlayerEngine] binding by the engine it builds. */
@MapKey annotation class PlayerEngineKey(val value: PlayerEngineId)

/**
 * The player engines, by [PlayerEngineId] (#764). The only place outside `engine.exo` and
 * `engine.mpv` that names them (rule R6).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {
    @Binds
    @IntoMap
    @PlayerEngineKey(PlayerEngineId.EXOPLAYER)
    abstract fun bindExoEngine(engine: ExoEngine): PlayerEngine

    @Binds
    @IntoMap
    @PlayerEngineKey(PlayerEngineId.MPV)
    abstract fun bindMpvEngine(engine: MpvEngine): PlayerEngine
}
