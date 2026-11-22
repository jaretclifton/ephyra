package dev.jdtech.jellyfin.models

/** CE (#252): stored and downloaded episode counts of one season, from the local database. */
data class SeasonEpisodeCounts(val seasonId: ItemId, val total: Int, val downloaded: Int)
