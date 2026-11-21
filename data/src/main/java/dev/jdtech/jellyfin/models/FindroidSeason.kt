package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import java.util.UUID

data class FindroidSeason(
    override val id: UUID,
    override val name: String,
    val seriesId: UUID,
    val seriesName: String,
    override val originalTitle: String?,
    override val overview: String,
    override val sources: List<FindroidSource>,
    val indexNumber: Int,
    val episodes: Collection<FindroidEpisode>,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val runtimeTicks: Long = 0L,
    override val playbackPositionTicks: Long = 0L,
    override val unplayedItemCount: Int?,
    override val images: FindroidImages,
    override val chapters: List<FindroidChapter> = emptyList(),
) : FindroidItem

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
