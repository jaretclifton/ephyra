package dev.jdtech.jellyfin.models

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/** The Room row of a [Server] (#899: the entity was `Server` before the domain had the model). */
@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val id: ServerId,
    val name: String,
    val currentServerAddressId: ServerAddressId?,
    val currentUserId: UserId?,
    /**
     * CE (#770): the kind of server, as [ServerType.value]. Every server saved before the column
     * existed is a Jellyfin server, so the migration gives them `jellyfin`.
     */
    @ColumnInfo(defaultValue = "jellyfin") val type: String = ServerType.JELLYFIN.value,
)

fun ServerEntity.toServer(): Server =
    Server(
        id = id,
        name = name,
        currentServerAddressId = currentServerAddressId,
        currentUserId = currentUserId,
        type = type,
    )

fun Server.toServerEntity(): ServerEntity =
    ServerEntity(
        id = id,
        name = name,
        currentServerAddressId = currentServerAddressId,
        currentUserId = currentUserId,
        type = type,
    )
