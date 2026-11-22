package dev.jdtech.jellyfin.server.jellyfin.mappers

import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidSource
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.PlayAccess

/**
 * For lists: [localSources] comes from one `getLocalSourcesForItems` call (in `data`) for all
 * items.
 */
internal suspend fun BaseItemDto.toFindroidEpisode(
    context: MapperContext,
    localSources: Map<UUID, List<FindroidSource>>,
): FindroidEpisode? {
    val sources = mutableListOf<FindroidSource>()
    sources.addAll(mediaSources?.map { it.toFindroidSource(context, id) } ?: emptyList())
    sources.addAll(localSources[id].orEmpty())
    return try {
        FindroidEpisode(
            id = id,
            name = name.orEmpty(),
            originalTitle = originalTitle,
            overview = overview.orEmpty(),
            indexNumber = indexNumber ?: 0,
            indexNumberEnd = indexNumberEnd,
            parentIndexNumber = parentIndexNumber ?: 0,
            sources = sources,
            played = userData?.played == true,
            favorite = userData?.isFavorite == true,
            canPlay = playAccess != PlayAccess.NONE,
            canDownload = canDownload == true,
            runtimeTicks = runTimeTicks ?: 0,
            playbackPositionTicks = userData?.playbackPositionTicks ?: 0L,
            premiereDate = premiereDate,
            seriesId = seriesId!!,
            seriesName = seriesName.orEmpty(),
            seasonId = seasonId!!,
            seasonName = seasonName,
            communityRating = communityRating,
            people = people?.map { it.toFindroidPerson(context.baseUrl) } ?: emptyList(),
            missing = locationType == LocationType.VIRTUAL,
            lastPlayedDate = userData?.lastPlayedDate,
            externalLinks = toExternalLinks(),
            playCount = userData?.playCount ?: 0,
            images = toFindroidImages(context.baseUrl),
            chapters = toFindroidChapters(),
            trickplayInfo = toFindroidTrickplayInfo(),
        )
    } catch (_: NullPointerException) {
        null
    }
}
