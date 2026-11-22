package dev.jdtech.jellyfin.film.presentation.downloads

import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.ItemId

data class DownloadedSeason(val number: Int, val name: String?, val episodes: List<FindroidEpisode>)

data class DownloadedShowState(
    val seasons: List<DownloadedSeason> = emptyList(),
    val itemSizes: Map<ItemId, Long> = emptyMap(),
    val isLoading: Boolean = true,
)
