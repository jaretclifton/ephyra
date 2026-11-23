package dev.jdtech.jellyfin.film.presentation.media

import dev.jdtech.jellyfin.models.Library

sealed interface MediaAction {
    data class OnItemClick(val item: Library) : MediaAction

    data object OnRetryClick : MediaAction
}
