package dev.jdtech.jellyfin.film.presentation.show

import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId

sealed interface ShowAction {
    data class Play(val startFromBeginning: Boolean = false) : ShowAction

    data object Shuffle : ShowAction

    data class PlayTrailer(val trailer: String) : ShowAction

    data object MarkAsPlayed : ShowAction

    data object UnmarkAsPlayed : ShowAction

    data object MarkAsFavorite : ShowAction

    data object UnmarkAsFavorite : ShowAction

    data object OnRetryClick : ShowAction

    data object OnBackClick : ShowAction

    data object OnHomeClick : ShowAction

    data class NavigateToItem(val item: Item) : ShowAction

    data class NavigateToPerson(val personId: ItemId) : ShowAction

    /** CE (#332): plays a local trailer or special feature in the app's player. */
    data class PlayExtra(val extraId: ItemId) : ShowAction

    /** CE (#332): opens the show on IMDb, TMDB, TVDB and the like. */
    data class OpenExternalLink(val url: String) : ShowAction
}
