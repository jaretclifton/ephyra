package dev.jdtech.jellyfin.server.jellyfin.mappers

import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.server.jellyfin.toItemId
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.PlayAccess

/**
 * For lists: [localSources] comes from one `getLocalSourcesForItems` call (in `data`) for all
 * items.
 */
internal suspend fun BaseItemDto.toEpisode(
    context: MapperContext,
    localSources: Map<ItemId, List<ItemSource>>,
): Episode? {
    val sources = mutableListOf<ItemSource>()
    sources.addAll(mediaSources?.map { it.toItemSource(context, id.toItemId()) } ?: emptyList())
    sources.addAll(localSources[id.toItemId()].orEmpty())
    return try {
        Episode(
            id = id.toItemId(),
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
            seriesId = seriesId!!.toItemId(),
            seriesName = seriesName.orEmpty(),
            seasonId = seasonId!!.toItemId(),
            seasonName = seasonName,
            communityRating = communityRating,
            people = people?.map { it.toPerson(context.baseUrl) } ?: emptyList(),
            missing = locationType == LocationType.VIRTUAL,
            lastPlayedDate = userData?.lastPlayedDate,
            externalLinks = toExternalLinks(),
            playCount = userData?.playCount ?: 0,
            images = toItemImages(context.baseUrl),
            chapters = toChapters(),
            trickplayInfo = toItemTrickplayInfo(),
        )
    } catch (_: NullPointerException) {
        null
    }
}
