package dev.jdtech.jellyfin.models

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import java.time.LocalDateTime

@Entity(
    tableName = "episodes",
    foreignKeys =
        [
            ForeignKey(
                entity = SeasonEntity::class,
                parentColumns = arrayOf("id"),
                childColumns = arrayOf("seasonId"),
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = ShowEntity::class,
                parentColumns = arrayOf("id"),
                childColumns = arrayOf("seriesId"),
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("seasonId"), Index("seriesId")],
)
data class EpisodeEntity(
    @PrimaryKey val id: ItemId,
    val serverId: String?,
    val seasonId: ItemId,
    val seriesId: ItemId,
    val name: String,
    val seriesName: String,
    val overview: String,
    val indexNumber: Int,
    val indexNumberEnd: Int?,
    val parentIndexNumber: Int,
    val runtimeTicks: Long,
    val premiereDate: LocalDateTime?,
    val communityRating: Float?,
    val chapters: List<Chapter>?,
)

fun Episode.toEpisodeEntity(serverId: String? = null): EpisodeEntity {
    return EpisodeEntity(
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

suspend fun EpisodeEntity.toEpisode(database: ServerDatabaseDao, userId: UserId): Episode =
    toEpisode(database, userId, database.getLocalSourcesForItems(listOf(id)))

/** For lists: [localSources] comes from one [getLocalSourcesForItems] call for all items. */
suspend fun EpisodeEntity.toEpisode(
    database: ServerDatabaseDao,
    userId: UserId,
    localSources: Map<ItemId, List<ItemSource>>,
): Episode {
    val userData = database.getUserDataOrCreateNew(id, userId)
    val sources = localSources[id].orEmpty()
    val trickplayInfos = mutableMapOf<String, ItemTrickplayInfo>()
    for (source in sources) {
        database.getTrickplayInfo(source.id)?.toItemTrickplayInfo()?.let {
            trickplayInfos[source.id] = it
        }
    }
    return Episode(
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
        images = toLocalItemImages(itemId = id),
        chapters = chapters ?: emptyList(),
        trickplayInfo = trickplayInfos,
    )
}

fun EpisodeEntity.toLocalItemImages(itemId: ItemId): ItemImages {
    return ItemImages(
        primary = localImagePath(itemId, "primary"),
        backdrop = localImagePath(itemId, "backdrop"),
        showPrimary = localImagePath(seriesId, "primary"),
        showBackdrop = localImagePath(seriesId, "backdrop"),
    )
}
