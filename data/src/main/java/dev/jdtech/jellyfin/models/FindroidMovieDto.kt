package dev.jdtech.jellyfin.models

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import java.time.LocalDateTime
import java.util.UUID

@Entity(tableName = "movies")
data class FindroidMovieDto(
    @PrimaryKey val id: UUID,
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
    val chapters: List<FindroidChapter>?,
    val genres: List<String>? = null,
)

fun FindroidMovie.toFindroidMovieDto(serverId: String? = null): FindroidMovieDto {
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

suspend fun FindroidMovieDto.toFindroidMovie(
    database: ServerDatabaseDao,
    userId: UUID,
): FindroidMovie = toFindroidMovie(database, userId, database.getLocalSourcesForItems(listOf(id)))

/** For lists: [localSources] comes from one [getLocalSourcesForItems] call for all items. */
suspend fun FindroidMovieDto.toFindroidMovie(
    database: ServerDatabaseDao,
    userId: UUID,
    localSources: Map<UUID, List<FindroidSource>>,
): FindroidMovie {
    val userData = database.getUserDataOrCreateNew(id, userId)
    val sources = localSources[id].orEmpty()
    val trickplayInfos = mutableMapOf<String, FindroidTrickplayInfo>()
    for (source in sources) {
        database.getTrickplayInfo(source.id)?.toFindroidTrickplayInfo()?.let {
            trickplayInfos[source.id] = it
        }
    }
    return FindroidMovie(
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
        images = toLocalFindroidImages(itemId = id),
        chapters = chapters ?: emptyList(),
        trickplayInfo = trickplayInfos,
    )
}

fun FindroidMovieDto.toLocalFindroidImages(itemId: UUID): FindroidImages {
    return FindroidImages(
        primary = localImagePath(itemId, "primary"),
        backdrop = localImagePath(itemId, "backdrop"),
    )
}
