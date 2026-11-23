package dev.jdtech.jellyfin.core.presentation.dummy

import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.toServerId

val dummyUser = User(id = randomUserId(), name = "Username", serverId = "".toServerId())

val dummyUsers = listOf(dummyUser)
