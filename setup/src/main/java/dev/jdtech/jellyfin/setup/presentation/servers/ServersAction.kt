package dev.jdtech.jellyfin.setup.presentation.servers

import dev.jdtech.jellyfin.models.ServerId
import java.util.UUID

sealed interface ServersAction {
    data class OnServerClick(val serverId: ServerId) : ServersAction

    data class OnAddressClick(val addressId: UUID) : ServersAction

    data class NavigateToAddresses(val serverId: ServerId) : ServersAction

    data class DeleteServer(val serverId: ServerId) : ServersAction

    data object OnAddClick : ServersAction

    data object OnBackClick : ServersAction
}
