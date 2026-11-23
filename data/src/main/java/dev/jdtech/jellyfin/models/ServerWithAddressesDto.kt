package dev.jdtech.jellyfin.models

import androidx.room3.Embedded
import androidx.room3.Relation

data class ServerWithAddressesDto(
    @Embedded val server: ServerEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["serverId"])
    val addresses: List<ServerAddressEntity>,
    @Relation(parentColumns = ["currentUserId"], entityColumns = ["id"]) val user: UserEntity?,
)

fun ServerWithAddressesDto.toServerWithAddresses(): ServerWithAddresses =
    ServerWithAddresses(server.toServer(), addresses.map { it.toServerAddress() }, user?.toUser())
