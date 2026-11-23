package dev.jdtech.jellyfin.models

import androidx.room3.Embedded
import androidx.room3.Relation

data class ServerWithAddressesAndUsersDto(
    @Embedded val server: ServerEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["serverId"])
    val addresses: List<ServerAddressEntity>,
    @Relation(parentColumns = ["id"], entityColumns = ["serverId"]) val users: List<UserEntity>,
)
