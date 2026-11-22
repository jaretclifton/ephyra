package dev.jdtech.jellyfin.setup.presentation.users

import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.setup.presentation.pin.PinPrompt

data class UsersState(
    val users: List<User> = emptyList(),
    val publicUsers: List<User> = emptyList(),
    val serverName: String? = null,
    val currentUserId: UserId? = null,
    /** CE (#299): the PIN asked before switching to a locked profile. */
    val pinPrompt: PinPrompt? = null,
)
