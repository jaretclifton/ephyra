package dev.jdtech.jellyfin.player.local.domain

import dev.jdtech.jellyfin.models.DownloadState
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.PlayerEngineId
import dev.jdtech.jellyfin.models.downloadState
import dev.jdtech.jellyfin.repository.PlaybackMedia
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.QualityNetwork
import dev.jdtech.jellyfin.settings.presentation.enums.AppVariant
import dev.jdtech.jellyfin.utils.NetworkConnectivity
import javax.inject.Inject

/**
 * CE (#766): decides how an item is asked for and which of the answers plays. The player knows
 * which engine it runs and which cap the user chose, so it tells the repository instead of the
 * repository reading the player preferences.
 */
class ResolvePlayback
@Inject
constructor(
    private val media: PlaybackMedia,
    private val appPreferences: AppPreferences,
    private val connectivity: NetworkConnectivity,
    private val appVariant: AppVariant,
) {
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
     * CE (#837): the network whose quality applies now. Mobile data is a metered network on the
     * phone app; a TV has no mobile data, so it keeps the one value it always had.
     */
    fun qualityNetwork(): QualityNetwork =
        if (appVariant == AppVariant.PHONE) QualityNetwork.of(connectivity.isMetered())
        else QualityNetwork.WIFI

    /**
     * The user-selected cap (player quality selector, or the settings) for the network in use now,
     * in bits per second. 0 = original.
     */
    fun maxBitrateSetting(): Int = storedMaxBitrate(qualityNetwork())

    /** CE (#837): the cap stored for [network]. */
    fun storedMaxBitrate(network: QualityNetwork): Int =
        appPreferences.getValue(appPreferences.maxBitrate(network))

    /**
     * CE (#837): stores [bitrate] as the cap for [network], as the player's quality picker does.
     */
    fun storeMaxBitrate(network: QualityNetwork, bitrate: Int) =
        appPreferences.setValue(appPreferences.maxBitrate(network), bitrate)

    /** [maxBitrateSetting] as the request has it: null for no cap. */
    fun maxBitrate(): Int? = maxBitrateSetting().takeIf { it > 0 }

    /**
     * CE (#834): the audio language the user prefers, as the settings store it ("nld"), or null. A
     * transcode carries one audio track, so it has to be asked for with the request.
     */
    fun preferredAudioLanguage(): String? =
        appPreferences.getValue(appPreferences.preferredAudioLanguage)

    /**
     * The sources to play [itemId] on the local player, with the target the settings have and the
     * cap [maxBitrate] (0 = original), by default the setting for the network in use when this is
     * called (a quality switch writes its cap first).
     */
    suspend fun sources(
        itemId: ItemId,
        mediaSourceId: MediaSourceId? = null,
        audioStreamIndex: Int? = null,
        maxBitrate: Int = maxBitrateSetting(),
    ): List<ItemSource> =
        media.getPlaybackSources(
            itemId = itemId,
            target = localTarget(),
            mediaSourceId = mediaSourceId,
            audioStreamIndex = audioStreamIndex,
            maxBitrate = maxBitrate.takeIf { it > 0 },
        )

    /**
     * The source that plays: a finished download first, else the first remote one. Null when there
     * is none. Only the sources with the id [mediaSourceId] count, unless none has it.
     */
    fun pick(sources: List<ItemSource>, mediaSourceId: MediaSourceId?): ItemSource? {
        // A downloaded copy shares its id with the server version, so it wins for that version too.
        val mediaSources = sources.filter { it.id == mediaSourceId }.ifEmpty { sources }
        // Never an unfinished download (#454): its partial file plays as far as it got, or not at
        // all.
        return mediaSources.firstOrNull { it.downloadState == DownloadState.FINISHED }
            ?: mediaSources.firstOrNull { it.type != ItemSourceType.LOCAL }
    }
}
