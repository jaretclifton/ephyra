package dev.jdtech.jellyfin.player.local.domain

import dev.jdtech.jellyfin.models.MediaSegmentAction
import dev.jdtech.jellyfin.models.SegmentType
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.Constants

/**
 * A [SegmentTracker] built from the media segment settings: the action per segment type, how long
 * the skip button shows, the next-episode threshold and the auto-skip mode. The local player and
 * Cast (#851) both build theirs here, so they read the settings the same way.
 */
fun segmentTrackerFrom(appPreferences: AppPreferences): SegmentTracker =
    SegmentTracker(
        actions =
            appPreferences.playerMediaSegmentsActions
                .mapNotNull { (type, preference) ->
                    val segmentType =
                        SegmentType.entries.firstOrNull { it.name == type }
                            ?: return@mapNotNull null
                    segmentType to
                        MediaSegmentAction.fromPreferenceValue(
                            appPreferences.getValue(preference),
                            default = MediaSegmentAction.IGNORE,
                        )
                }
                .toMap(),
        buttonDurationMs =
            appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButtonDuration) * 1000L,
        nextEpisodeThresholdMs =
            appPreferences.getValue(appPreferences.playerMediaSegmentsNextEpisodeThreshold),
        autoSkipOnlyInPip =
            appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkipMode) ==
                Constants.PlayerMediaSegmentsAutoSkip.PIP,
    )
