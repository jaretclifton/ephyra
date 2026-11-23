package dev.jdtech.jellyfin.film.presentation.season

import dev.jdtech.jellyfin.core.presentation.downloader.DownloadProgress
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.Season

data class SeasonState(
    val season: Season? = null,
    val episodes: List<Episode> = emptyList(),
    /** All seasons of the series, only loaded with `includeSeriesSeasons` (TV season tabs). */
    val seriesSeasons: List<Season> = emptyList(),
    val episodeDownloadProgress: Map<ItemId, DownloadProgress> = emptyMap(),
    val error: Exception? = null,
    /**
     * #769: whether a download can be transcoded down to a chosen quality. Without it the quality
     * dialog offers the original only.
     */
    val cappedDownloads: Boolean = true,
    /** CE (#505): a load is running, so empty [episodes] may still be coming. */
    val isLoading: Boolean = false,
    /**
     * CE (#281): [episodes] are the stored (downloaded) episodes only: the downloads-only page, or
     * the server did not answer. A removed download then leaves the list instead of staying in it
     * as not downloaded.
     */
    val storedOnly: Boolean = false,
)
