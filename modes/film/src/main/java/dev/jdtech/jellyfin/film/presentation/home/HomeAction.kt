package dev.jdtech.jellyfin.film.presentation.home

import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.Library

sealed interface HomeAction {
    data class OnItemClick(val item: Item) : HomeAction

    data class OnLibraryClick(val library: Library) : HomeAction

    data object OnRetryClick : HomeAction

    data object OnSearchClick : HomeAction

    data object OnSettingsClick : HomeAction

    data object OnManageServers : HomeAction
}
