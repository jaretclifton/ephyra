package dev.jdtech.jellyfin.player.local.domain

import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.PlayerEngineId
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject

/**
 * CE (#766): decides how an item is asked for and which of the answers plays. The player knows
 * which engine it runs and which cap the user chose, so it tells the repository instead of the
 * repository reading the player preferences.
 */
class ResolvePlayback
@Inject
constructor(private val repository: MediaRepository, private val appPreferences: AppPreferences) {
    /**
     * The target of the local player as the settings have it now. Any backend value other than
     * mpv's plays on ExoPlayer, as before.
     */
    fun localTarget(): PlaybackTarget.LocalPlayer =
        PlaybackTarget.LocalPlayer(
            engine =
                PlayerEngineId.fromPreference(appPreferences.getValue(appPreferences.playerBackend))
                    ?: PlayerEngineId.EXOPLAYER,
            directPlayDolbyVision =
                appPreferences.getValue(appPreferences.playerDirectPlayDolbyVision),
        )

    /**
     * The user-selected cap (player quality selector) in bits per second, or null. 0 = original.
     */
    fun maxBitrate(): Int? =
        appPreferences.getValue(appPreferences.playerMaxBitrate).takeIf { it > 0 }

    /**
     * CE (#834): the audio language the user prefers, as the settings store it ("nld"), or null. A
     * transcode carries one audio track, so it has to be asked for with the request.
     */
    fun preferredAudioLanguage(): String? =
        appPreferences.getValue(appPreferences.preferredAudioLanguage)

    /**
     * The sources to play [itemId] on the local player, with the target and cap the settings have
     * when this is called (a quality switch writes its cap first).
     */
    suspend fun sources(
        itemId: ItemId,
        mediaSourceId: String? = null,
        audioStreamIndex: Int? = null,
    ): List<FindroidSource> {
        // The cap first, then the target: the order the repository read them in before #766.
        val maxBitrate = maxBitrate()
        return repository.getPlaybackSources(
            itemId = itemId,
            target = localTarget(),
            mediaSourceId = mediaSourceId,
            audioStreamIndex = audioStreamIndex,
            maxBitrate = maxBitrate,
        )
    }

    /**
     * The source that plays: a finished download first, else the first remote one. Null when there
     * is none. Only the sources with the id [mediaSourceId] count, unless none has it.
     */
    fun pick(sources: List<FindroidSource>, mediaSourceId: String?): FindroidSource? {
        // A downloaded copy shares its id with the server version, so it wins for that version too.
        val mediaSources = sources.filter { it.id == mediaSourceId }.ifEmpty { sources }
        // Never an unfinished download (#454): its partial file plays as far as it got, or not at
        // all.
        return mediaSources.firstOrNull {
            it.type == FindroidSourceType.LOCAL && !it.path.endsWith(".download")
        } ?: mediaSources.firstOrNull { it.type != FindroidSourceType.LOCAL }
    }
}
