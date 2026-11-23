package dev.jdtech.jellyfin.models

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import java.time.LocalDateTime

@Entity(tableName = "movies")
data class FindroidMovieDto(
    @PrimaryKey val id: ItemId,
    val serverId: String?,
    val name: String,
    val originalTitle: String?,
    val overview: String,
    val runtimeTicks: Long,
    val premiereDate: LocalDateTime?,
    val communityRating: Float?,
    val officialRating: String?,
    val status: String,
    val productionYear: Int?,
    val endDate: LocalDateTime?,
    val chapters: List<Chapter>?,
    val genres: List<String>? = null,
)

fun Movie.toFindroidMovieDto(serverId: String? = null): FindroidMovieDto {
    return FindroidMovieDto(
        id = id,
        serverId = serverId,
        name = name,
        originalTitle = originalTitle,
        overview = overview,
        runtimeTicks = runtimeTicks,
        premiereDate = premiereDate,
        communityRating = communityRating,
        officialRating = officialRating,
        status = status,
        productionYear = productionYear,
        endDate = endDate,
        chapters = chapters,
        genres = genres,
    )
}

suspend fun FindroidMovieDto.toMovie(database: ServerDatabaseDao, userId: UserId): Movie =
    toMovie(database, userId, database.getLocalSourcesForItems(listOf(id)))

/** For lists: [localSources] comes from one [getLocalSourcesForItems] call for all items. */
suspend fun FindroidMovieDto.toMovie(
    database: ServerDatabaseDao,
    userId: UserId,
    localSources: Map<ItemId, List<ItemSource>>,
): Movie {
    val userData = database.getUserDataOrCreateNew(id, userId)
    val sources = localSources[id].orEmpty()
    val trickplayInfos = mutableMapOf<String, ItemTrickplayInfo>()
    for (source in sources) {
        database.getTrickplayInfo(source.id)?.toItemTrickplayInfo()?.let {
            trickplayInfos[source.id] = it
        }
    }
    return Movie(
        id = id,
        name = name,
        originalTitle = originalTitle,
        overview = overview,
        played = userData.played,
        favorite = userData.favorite,
        runtimeTicks = runtimeTicks,
        playbackPositionTicks = userData.playbackPositionTicks,
        premiereDate = premiereDate,
        genres = genres ?: emptyList(),
        people = emptyList(),
        communityRating = communityRating,
        officialRating = officialRating,
        status = status,
        productionYear = productionYear,
        endDate = endDate,
        canDownload = false,
        canPlay = true,
        sources = sources,
        trailer = null,
        images = toLocalItemImages(itemId = id),
        chapters = chapters ?: emptyList(),
        trickplayInfo = trickplayInfos,
    )
}

fun FindroidMovieDto.toLocalItemImages(itemId: ItemId): ItemImages {
    return ItemImages(
        primary = localImagePath(itemId, "primary"),
        backdrop = localImagePath(itemId, "backdrop"),
    )
}
