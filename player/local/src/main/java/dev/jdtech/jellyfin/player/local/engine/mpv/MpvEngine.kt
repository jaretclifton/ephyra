package dev.jdtech.jellyfin.player.local.engine.mpv

import android.app.Application
import androidx.media3.common.Player
import dev.jdtech.jellyfin.models.PlayerEngineId
import dev.jdtech.jellyfin.player.local.engine.EngineDefaults
import dev.jdtech.jellyfin.player.local.engine.EnginePlayer
import dev.jdtech.jellyfin.player.local.engine.PlayerEngine
import dev.jdtech.jellyfin.player.local.engine.PlayerEngineConfig
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject
import kotlinx.coroutines.launch

/** libmpv through [MPVPlayer] (#764). */
class MpvEngine
@Inject
constructor(private val application: Application, private val appPreferences: AppPreferences) :
    PlayerEngine {
    override val id = PlayerEngineId.MPV

    override fun create(config: PlayerEngineConfig): EnginePlayer {
        val trackSelector = EngineDefaults.trackSelector(application, appPreferences)
        val audioAttributes = EngineDefaults.audioAttributes()

        val player =
            MPVPlayer.Builder(application)
                .setAudioAttributes(audioAttributes, true)
                .setTrackSelectionParameters(trackSelector.parameters)
                .setSeekBackIncrementMs(appPreferences.getValue(appPreferences.playerSeekBackInc))
                .setSeekForwardIncrementMs(
                    appPreferences.getValue(appPreferences.playerSeekForwardInc)
                )
                .setPauseAtEndOfMediaItems(true)
                .setVideoOutput(appPreferences.getValue(appPreferences.playerMpvVo))
                .setAudioOutput(appPreferences.getValue(appPreferences.playerMpvAo))
                .setHwDec(appPreferences.getValue(appPreferences.playerMpvHwdec))
                .setMatchRefreshRate(
                    appPreferences.getValue(appPreferences.playerTvMatchRefreshRate)
                )
                .setSubtitleOptions(appPreferences.mpvSubtitleOptions())
                // CE (#711): the subtitle files go over the API's client, as on ExoPlayer.
                .setSubtitleHttpClient(config.httpClient)
                .build()

        // CE (#300, #308): mpv takes a subtitle setting changed while it plays (from the settings,
        // with the player in picture-in-picture). ExoPlayer's SubtitleView is styled when the
        // player screen opens.
        config.scope.launch {
            appPreferences.mpvSubtitleOptionChanges().collect { player.updateSubtitleOptions(it) }
        }
        return MpvEnginePlayer(player)
    }
}

/** All calls on the main thread, as from `PlayerViewModel`. */
private class MpvEnginePlayer(private val mpvPlayer: MPVPlayer) : EnginePlayer {
    override val player: Player
        get() = mpvPlayer

    // CE (#870): mpv reports a transition only when the item changes (#453, MpvItemTransitions).
    override val transitionsOnReplacedPlaylist: Boolean
        get() = false

    /** The delay last set, which mpv drops when it loads a file. */
    private var subtitleDelayMs = 0

    override fun setSubtitleDelayMs(delayMs: Int) {
        subtitleDelayMs = delayMs
        mpvPlayer.setSubtitleDelayMs(delayMs)
    }

    // CE (#619): mpv sets sub-delay back to 0 whenever it loads a file, also a reload
    // of the same item that keeps the delay.
    override fun onItemReady() {
        if (subtitleDelayMs != 0) {
            mpvPlayer.setSubtitleDelayMs(subtitleDelayMs)
        }
    }

    override fun setZoomMode(enabled: Boolean): Boolean {
        mpvPlayer.updateZoomMode(enabled)
        return true
    }
}
