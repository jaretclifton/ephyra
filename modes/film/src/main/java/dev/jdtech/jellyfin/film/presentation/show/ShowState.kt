package dev.jdtech.jellyfin.film.presentation.show

import dev.jdtech.jellyfin.film.presentation.RelatedItems
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemPerson
import dev.jdtech.jellyfin.models.Season
import dev.jdtech.jellyfin.models.Show

data class SeasonDownloadInfo(
    val downloadedCount: Int = 0,
    val totalCount: Int = 0,
    /** Whether the server allows at least one of the season's episodes to be downloaded (#373). */
    val canDownload: Boolean = false,
)

data class ShowState(
    val show: Show? = null,
    val nextUp: Episode? = null,
    val seasons: List<Season> = emptyList(),
    val seasonDownloadInfo: Map<ItemId, SeasonDownloadInfo> = emptyMap(),
    val actors: List<ItemPerson> = emptyList(),
    val director: ItemPerson? = null,
    val writers: List<ItemPerson> = emptyList(),
    val hasDownloads: Boolean = false,
    // CE (#141): the TV show page's theme song, null when off or the server has none.
    val themeSongUrl: String? = null,
    // CE (#332): loaded after the page is shown, see ShowViewModel.loadRelated.
    val related: RelatedItems = RelatedItems(),
    val error: Exception? = null,
    /**
     * #769: whether a download can be transcoded down to a chosen quality. Without it the quality
     * dialog offers the original only.
     */
    val cappedDownloads: Boolean = true,
) {
    /**
     * Whether the page offers Download (#373): known once the series' episodes are in, so a page
     * without them (the request failed, or offline) offers none.
     */
    val canDownload: Boolean
        get() = seasonDownloadInfo.values.any { it.canDownload }

    /** The seasons the season picker of a show download lists. */
    val downloadableSeasons: List<Season>
        get() = seasons.filter { seasonDownloadInfo[it.id]?.canDownload == true }
}
