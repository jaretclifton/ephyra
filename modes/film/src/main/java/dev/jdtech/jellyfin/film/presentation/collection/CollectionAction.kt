package dev.jdtech.jellyfin.film.presentation.collection

import dev.jdtech.jellyfin.models.Item

sealed interface CollectionAction {
    data class OnItemClick(val item: Item) : CollectionAction

    data object OnRetryClick : CollectionAction

    data object OnBackClick : CollectionAction
}
