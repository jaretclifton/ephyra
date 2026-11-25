package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.SeriesTrackPreference

/**
 * #967: the audio and subtitle tracks the user picked by hand in a series (CE, #580). One of the
 * roles the repository was split into; kept in Room, per user.
 */
interface SeriesTrackPreferences {
    /** CE (#580): the tracks the user last picked by hand in [seriesId], one per track type. */
    suspend fun getSeriesTrackPreferences(seriesId: ItemId): List<SeriesTrackPreference>

    /**
     * Remembers [preference] as the current user's choice for its series and track type, replacing
     * the one before. The current user is written whatever [preference] says (#750).
     */
    suspend fun saveSeriesTrackPreference(preference: SeriesTrackPreference)
}
