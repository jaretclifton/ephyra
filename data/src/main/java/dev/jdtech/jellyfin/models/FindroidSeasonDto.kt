package dev.jdtech.jellyfin.models

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import java.util.UUID

@Entity(
    tableName = "seasons",
    foreignKeys =
        [
            ForeignKey(
                entity = FindroidShowDto::class,
                parentColumns = arrayOf("id"),
                childColumns = arrayOf("seriesId"),
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("seriesId")],
)
data class FindroidSeasonDto(
    @PrimaryKey val id: UUID,
    val seriesId: UUID,
    val name: String,
    val seriesName: String,
    val overview: String,
    val indexNumber: Int,
)

fun FindroidSeason.toFindroidSeasonDto(): FindroidSeasonDto {
    return FindroidSeasonDto(
        id = id,
        seriesId = seriesId,
        name = name,
        seriesName = seriesName,
        overview = overview,
        indexNumber = indexNumber,
    )
}

suspend fun FindroidSeasonDto.toFindroidSeason(
    database: ServerDatabaseDao,
    userId: UUID,
): FindroidSeason {
    val userData = database.getUserDataOrCreateNew(id, userId)
    return FindroidSeason(
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
        images = toLocalFindroidImages(itemId = id),
    )
}

fun FindroidSeasonDto.toLocalFindroidImages(itemId: UUID): FindroidImages {
    return FindroidImages(
        primary = localImagePath(itemId, "primary"),
        backdrop = localImagePath(itemId, "backdrop"),
        showPrimary = localImagePath(seriesId, "primary"),
        showBackdrop = localImagePath(seriesId, "backdrop"),
    )
}
