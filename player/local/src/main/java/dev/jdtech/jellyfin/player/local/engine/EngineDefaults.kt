package dev.jdtech.jellyfin.player.local.engine

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dev.jdtech.jellyfin.settings.domain.AppPreferences

/**
 * The settings both engines start with (#764: from `PlayerViewModel`'s init, where they were built
 * once for whichever engine ran).
 */
internal object EngineDefaults {
    fun audioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()

    /** ExoPlayer selects tracks with it; mpv takes its parameters. */
    fun trackSelector(context: Context, appPreferences: AppPreferences): DefaultTrackSelector {
        val trackSelector = DefaultTrackSelector(context)
        trackSelector.setParameters(
            trackSelector
                .buildUponParameters()
                .setTunnelingEnabled(true)
                .setPreferredAudioLanguage(
                    appPreferences.getValue(appPreferences.preferredAudioLanguage)
                )
                .setPreferredTextLanguage(
                    appPreferences.getValue(appPreferences.preferredSubtitleLanguage)
                )
        )
        return trackSelector
    }
}
