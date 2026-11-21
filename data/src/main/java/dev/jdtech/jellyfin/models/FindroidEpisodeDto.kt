package dev.jdtech.jellyfin.models

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import java.time.LocalDateTime
import java.util.UUID

@Entity(
    tableName = "episodes",
    foreignKeys =
        [
            ForeignKey(
                entity = FindroidSeasonDto::class,
                parentColumns = arrayOf("id"),
                childColumns = arrayOf("seasonId"),
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = FindroidShowDto::class,
                parentColumns = arrayOf("id"),
                childColumns = arrayOf("seriesId"),
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("seasonId"), Index("seriesId")],
)
data class FindroidEpisodeDto(
    @PrimaryKey val id: UUID,
    val serverId: String?,
    val seasonId: UUID,
    val seriesId: UUID,
    val name: String,
    val seriesName: String,
    val overview: String,
    val indexNumber: Int,
    val indexNumberEnd: Int?,
    val parentIndexNumber: Int,
    val runtimeTicks: Long,
    val premiereDate: LocalDateTime?,
    val communityRating: Float?,
    val chapters: List<FindroidChapter>?,
)

fun FindroidEpisode.toFindroidEpisodeDto(serverId: String? = null): FindroidEpisodeDto {
    return FindroidEpisodeDto(
        id = id,
        serverId = serverId,
        seasonId = seasonId,
        seriesId = seriesId,
        name = name,
        seriesName = seriesName,
        overview = overview,
        indexNumber = indexNumber,
        indexNumberEnd = indexNumberEnd,
        parentIndexNumber = parentIndexNumber,
        runtimeTicks = runtimeTicks,
        premiereDate = premiereDate,
        communityRating = communityRating,
        chapters = chapters,
    )
}

suspend fun FindroidEpisodeDto.toFindroidEpisode(
    database: ServerDatabaseDao,
    userId: UUID,
): FindroidEpisode =
    toFindroidEpisode(database, userId, database.getLocalSourcesForItems(listOf(id)))

/** For lists: [localSources] comes from one [getLocalSourcesForItems] call for all items. */
suspend fun FindroidEpisodeDto.toFindroidEpisode(
    database: ServerDatabaseDao,
    userId: UUID,
    localSources: Map<UUID, List<FindroidSource>>,
): FindroidEpisode {
    val userData = database.getUserDataOrCreateNew(id, userId)
    val sources = localSources[id].orEmpty()
    val trickplayInfos = mutableMapOf<String, FindroidTrickplayInfo>()
    for (source in sources) {
        database.getTrickplayInfo(source.id)?.toFindroidTrickplayInfo()?.let {
            trickplayInfos[source.id] = it
        }
    }
    return FindroidEpisode(
        id = id,
        name = name,
        originalTitle = "",
        overview = overview,
        indexNumber = indexNumber,
        indexNumberEnd = indexNumberEnd,
        parentIndexNumber = parentIndexNumber,
        sources = sources,
        played = userData.played,
        favorite = userData.favorite,
        canPlay = true,
        canDownload = false,
        runtimeTicks = runtimeTicks,
        playbackPositionTicks = userData.playbackPositionTicks,
        premiereDate = premiereDate,
        seriesId = seriesId,
        seriesName = seriesName,
        seasonId = seasonId,
        seasonName = null,
        communityRating = communityRating,
        people = emptyList(),
        images = toLocalFindroidImages(itemId = id),
        chapters = chapters ?: emptyList(),
        trickplayInfo = trickplayInfos,
    )
}

fun FindroidEpisodeDto.toLocalFindroidImages(itemId: UUID): FindroidImages {
    return FindroidImages(
        primary = localImagePath(itemId, "primary"),
        backdrop = localImagePath(itemId, "backdrop"),
        showPrimary = localImagePath(seriesId, "primary"),
        showBackdrop = localImagePath(seriesId, "backdrop"),
    )
}
