package dev.jdtech.jellyfin.setup.presentation.servers

import dev.jdtech.jellyfin.models.ServerAddressId
import dev.jdtech.jellyfin.models.ServerId

sealed interface ServersAction {
    data class OnServerClick(val serverId: ServerId) : ServersAction

    data class OnAddressClick(val addressId: ServerAddressId) : ServersAction

    data class NavigateToAddresses(val serverId: ServerId) : ServersAction

    data class DeleteServer(val serverId: ServerId) : ServersAction

    data object OnAddClick : ServersAction

    data object OnBackClick : ServersAction
}
