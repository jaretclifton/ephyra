package dev.jdtech.jellyfin.film.presentation.season

import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId

sealed interface SeasonAction {
    data class Play(val startFromBeginning: Boolean = false) : SeasonAction

    /** CE (#638): plays the season's episodes in random order. */
    data object Shuffle : SeasonAction

    data object MarkAsPlayed : SeasonAction

    data object UnmarkAsPlayed : SeasonAction

    data object MarkAsFavorite : SeasonAction

    data object UnmarkAsFavorite : SeasonAction

    data object OnRetryClick : SeasonAction

    data object OnBackClick : SeasonAction

    data object OnHomeClick : SeasonAction

    data class NavigateToItem(val item: Item) : SeasonAction

    data class NavigateToSeries(val seriesId: ItemId) : SeasonAction

    /** [maxBitrate] = download quality cap in bits/sec, null for original quality. */
    data class DownloadEpisode(val episode: Item, val maxBitrate: Int? = null) : SeasonAction

    data class DeleteEpisodeDownload(val episode: Item) : SeasonAction

    data class RetryEpisodeDownload(val episode: Item) : SeasonAction
}
