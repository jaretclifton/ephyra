package dev.jdtech.jellyfin.film.presentation.episode

import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.ItemPerson
import dev.jdtech.jellyfin.models.VideoMetadata

data class EpisodeState(
    val episode: Episode? = null,
    val videoMetadata: VideoMetadata? = null,
    val actors: List<ItemPerson> = emptyList(),
    val error: Exception? = null,
    /**
     * #769: whether a download can be transcoded down to a chosen quality. Without it the quality
     * dialog offers the original only.
     */
    val cappedDownloads: Boolean = true,
    /**
     * CE (#630): the "Use episode images" setting. The phone page's header shows the episode's own
     * image with it and the show's backdrop without, like the card that led here.
     */
    val useEpisodeImages: Boolean = false,
)
