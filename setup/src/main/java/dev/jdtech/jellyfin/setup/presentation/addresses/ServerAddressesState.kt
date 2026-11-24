package dev.jdtech.jellyfin.setup.presentation.addresses

import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerAddressId
import dev.jdtech.jellyfin.models.UiText

data class ServerAddressesState(
    val addresses: List<ServerAddress> = emptyList(),
    /** CE (#984): the address the server uses, which the screen marks. */
    val currentAddressId: ServerAddressId? = null,
    val error: Collection<UiText>? = null,
)
