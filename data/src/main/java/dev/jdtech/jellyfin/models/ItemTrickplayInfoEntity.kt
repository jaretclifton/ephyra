package dev.jdtech.jellyfin.models

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey

@Entity(
    tableName = "trickplayInfos",
    foreignKeys =
        [
            ForeignKey(
                entity = ItemSourceEntity::class,
                parentColumns = arrayOf("id"),
                childColumns = arrayOf("sourceId"),
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class ItemTrickplayInfoEntity(
    @PrimaryKey val sourceId: String,
    val width: Int,
    val height: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val thumbnailCount: Int,
    val interval: Int,
    val bandwidth: Int,
)

fun ItemTrickplayInfo.toItemTrickplayInfoEntity(sourceId: String): ItemTrickplayInfoEntity {
    return ItemTrickplayInfoEntity(
        sourceId = sourceId,
        width = width,
        height = height,
        tileWidth = tileWidth,
        tileHeight = tileHeight,
        thumbnailCount = thumbnailCount,
        interval = interval,
        bandwidth = bandwidth,
    )
}

fun ItemTrickplayInfoEntity.toItemTrickplayInfo(): ItemTrickplayInfo {
    return ItemTrickplayInfo(
        width = width,
        height = height,
        tileWidth = tileWidth,
        tileHeight = tileHeight,
        thumbnailCount = thumbnailCount,
        interval = interval,
        bandwidth = bandwidth,
    )
}
