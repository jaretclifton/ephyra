package dev.jdtech.jellyfin.film.presentation.episode

import dev.jdtech.jellyfin.models.ItemId

sealed interface EpisodeAction {
    data class Play(val startFromBeginning: Boolean = false, val mediaSourceId: String? = null) :
        EpisodeAction

    data object MarkAsPlayed : EpisodeAction

    data object UnmarkAsPlayed : EpisodeAction

    data object MarkAsFavorite : EpisodeAction

    data object UnmarkAsFavorite : EpisodeAction

    data object OnRetryClick : EpisodeAction

    data object OnBackClick : EpisodeAction

    data object OnHomeClick : EpisodeAction

    data class NavigateToPerson(val personId: ItemId) : EpisodeAction

    data class NavigateToSeason(val seasonId: ItemId, val seriesId: ItemId) : EpisodeAction

    /** CE (#395): the show's page, from the show button in the episode page's top bar. */
    data class NavigateToSeries(val seriesId: ItemId) : EpisodeAction

    /** CE (#332): opens the episode on IMDb and the like. */
    data class OpenExternalLink(val url: String) : EpisodeAction
}
