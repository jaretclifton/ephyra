package dev.jdtech.jellyfin.core.presentation.dummy

import dev.jdtech.jellyfin.models.User

val dummyUser = User(id = randomUserId(), name = "Username", serverId = "")

val dummyUsers = listOf(dummyUser)
