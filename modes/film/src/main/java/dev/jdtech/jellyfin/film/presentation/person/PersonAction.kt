package dev.jdtech.jellyfin.film.presentation.person

import dev.jdtech.jellyfin.models.Item

sealed interface PersonAction {
    data object OnRetryClick : PersonAction

    data object NavigateBack : PersonAction

    data object NavigateHome : PersonAction

    data class NavigateToItem(val item: Item) : PersonAction
}
