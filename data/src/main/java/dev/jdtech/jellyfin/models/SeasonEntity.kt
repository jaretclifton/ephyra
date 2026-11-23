package dev.jdtech.jellyfin.models

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.jdtech.jellyfin.database.ServerDatabaseDao

@Entity(
    tableName = "seasons",
    foreignKeys =
        [
            ForeignKey(
                entity = ShowEntity::class,
                parentColumns = arrayOf("id"),
                childColumns = arrayOf("seriesId"),
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("seriesId")],
)
data class SeasonEntity(
    @PrimaryKey val id: ItemId,
    val seriesId: ItemId,
    val name: String,
    val seriesName: String,
    val overview: String,
    val indexNumber: Int,
)

fun Season.toSeasonEntity(): SeasonEntity {
    return SeasonEntity(
        id = id,
        seriesId = seriesId,
        name = name,
        seriesName = seriesName,
        overview = overview,
        indexNumber = indexNumber,
    )
}

suspend fun SeasonEntity.toSeason(database: ServerDatabaseDao, userId: UserId): Season {
    val userData = database.getUserDataOrCreateNew(id, userId)
    return Season(
        id = id,
        name = name,
        originalTitle = null,
        overview = overview,
        played = userData.played,
        favorite = userData.favorite,
        canPlay = true,
        canDownload = false,
        unplayedItemCount = null,
        indexNumber = indexNumber,
        sources = emptyList(),
        episodes = emptyList(),
        seriesId = seriesId,
        seriesName = seriesName,
        images = toLocalItemImages(itemId = id),
    )
}

fun SeasonEntity.toLocalItemImages(itemId: ItemId): ItemImages {
    return ItemImages(
        primary = localImagePath(itemId, "primary"),
        backdrop = localImagePath(itemId, "backdrop"),
        showPrimary = localImagePath(seriesId, "primary"),
        showBackdrop = localImagePath(seriesId, "backdrop"),
    )
}
