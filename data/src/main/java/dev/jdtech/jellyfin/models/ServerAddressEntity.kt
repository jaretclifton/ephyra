package dev.jdtech.jellyfin.models

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import java.util.UUID

/** The Room row of a [ServerAddress] (#899). */
@Entity(
    tableName = "serverAddresses",
    foreignKeys =
        [
            ForeignKey(
                entity = ServerEntity::class,
                parentColumns = arrayOf("id"),
                childColumns = arrayOf("serverId"),
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class ServerAddressEntity(
    @PrimaryKey val id: UUID,
    @ColumnInfo(index = true) val serverId: ServerId,
    val address: String,
)

fun ServerAddressEntity.toServerAddress(): ServerAddress =
    ServerAddress(id = id, serverId = serverId, address = address)

fun ServerAddress.toServerAddressEntity(): ServerAddressEntity =
    ServerAddressEntity(id = id, serverId = serverId, address = address)
