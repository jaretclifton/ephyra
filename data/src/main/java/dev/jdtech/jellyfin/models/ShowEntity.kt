package dev.jdtech.jellyfin.models

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import java.time.LocalDateTime

@Entity(tableName = "shows")
data class ShowEntity(
    @PrimaryKey val id: ItemId,
    val serverId: ServerId?,
    val name: String,
    val originalTitle: String?,
    val overview: String,
    val runtimeTicks: Long,
    val communityRating: Float?,
    val officialRating: String?,
    val status: String,
    val productionYear: Int?,
    val endDate: LocalDateTime?,
    val genres: List<String>? = null,
)

fun Show.toShowEntity(serverId: ServerId? = null): ShowEntity {
    return ShowEntity(
        id = id,
        serverId = serverId,
        name = name,
        originalTitle = originalTitle,
        overview = overview,
        runtimeTicks = runtimeTicks,
        communityRating = communityRating,
        officialRating = officialRating,
        status = status,
        productionYear = productionYear,
        endDate = endDate,
        genres = genres,
    )
}

suspend fun ShowEntity.toShow(database: ServerDatabaseDao, userId: UserId): Show {
    val userData = database.getUserDataOrCreateNew(id, userId)
    return Show(
        id = id,
        name = name,
        originalTitle = originalTitle,
        overview = overview,
        played = userData.played,
        favorite = userData.favorite,
        canPlay = true,
        canDownload = false,
        unplayedItemCount = null,
        sources = emptyList(),
        seasons = emptyList(),
        genres = genres ?: emptyList(),
        people = emptyList(),
        runtimeTicks = runtimeTicks,
        communityRating = communityRating,
        officialRating = officialRating,
        status = status,
        productionYear = productionYear,
        endDate = endDate,
        trailer = null,
        images = toLocalItemImages(itemId = id),
    )
}

fun ShowEntity.toLocalItemImages(itemId: ItemId): ItemImages {
    return ItemImages(
        primary = localImagePath(itemId, "primary"),
        backdrop = localImagePath(itemId, "backdrop"),
    )
}
