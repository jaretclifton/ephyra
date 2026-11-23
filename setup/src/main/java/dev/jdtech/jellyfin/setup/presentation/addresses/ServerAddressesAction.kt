package dev.jdtech.jellyfin.setup.presentation.addresses

import dev.jdtech.jellyfin.models.ServerAddressId

sealed interface ServerAddressesAction {
    data class OnServerClick(val addressId: ServerAddressId) : ServerAddressesAction

    data class AddAddress(val address: String) : ServerAddressesAction

    data class DeleteAddress(val addressId: ServerAddressId) : ServerAddressesAction

    data object OnBackClick : ServerAddressesAction
}
