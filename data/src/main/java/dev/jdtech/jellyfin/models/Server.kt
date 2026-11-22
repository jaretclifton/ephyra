package dev.jdtech.jellyfin.models

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import java.util.UUID

@Entity(tableName = "servers")
data class Server(
    @PrimaryKey val id: String,
    val name: String,
    var currentServerAddressId: UUID?,
    var currentUserId: UserId?,
    /**
     * CE (#770): the kind of server, as [ServerType.value]. Every server saved before the column
     * existed is a Jellyfin server, so the migration gives them `jellyfin`. Read it through
     * [serverType].
     */
    @ColumnInfo(defaultValue = "jellyfin") val type: String = ServerType.JELLYFIN.value,
)

/** CE (#770): the type of [Server], or null when this version does not know [Server.type]. */
val Server.serverType: ServerType?
    get() = ServerType.fromValue(type)
