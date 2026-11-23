package dev.jdtech.jellyfin.models

import androidx.room3.Embedded
import androidx.room3.Relation

data class ServerWithAddressAndUserDto(
    @Embedded val server: ServerEntity,
    @Relation(parentColumns = ["currentServerAddressId"], entityColumns = ["id"])
    val address: ServerAddressEntity?,
    @Relation(parentColumns = ["currentUserId"], entityColumns = ["id"]) val user: UserEntity?,
)

fun ServerWithAddressAndUserDto.toServerWithAddressAndUser(): ServerWithAddressAndUser =
    ServerWithAddressAndUser(server.toServer(), address?.toServerAddress(), user?.toUser())
