package dev.jdtech.jellyfin.film.presentation.movie

import dev.jdtech.jellyfin.film.presentation.ExternalRatingsState
import dev.jdtech.jellyfin.film.presentation.RelatedItems
import dev.jdtech.jellyfin.models.ItemPerson
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.VideoMetadata

data class MovieState(
    val movie: Movie? = null,
    val videoMetadata: VideoMetadata? = null,
    val actors: List<ItemPerson> = emptyList(),
    val director: ItemPerson? = null,
    val writers: List<ItemPerson> = emptyList(),
    // CE (#332): loaded after the page is shown, see MovieViewModel.loadRelated.
    val related: RelatedItems = RelatedItems(),
    // CE (#331): the external ratings, loaded after the page is shown.
    val externalRatings: ExternalRatingsState = ExternalRatingsState.Hidden,
    val error: Exception? = null,
    /**
     * #769: whether a download can be transcoded down to a chosen quality. Without it the quality
     * dialog offers the original only.
     */
    val cappedDownloads: Boolean = true,
)
