package dev.jdtech.jellyfin.server.jellyfin.mappers

import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.firstTagline
import dev.jdtech.jellyfin.server.jellyfin.toItemId
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.PlayAccess

/**
 * For lists: [localSources] comes from one `getLocalSourcesForItems` call (in `data`) for all
 * items.
 */
internal suspend fun BaseItemDto.toFindroidMovie(
    context: MapperContext,
    localSources: Map<ItemId, List<FindroidSource>>,
): FindroidMovie {
    val sources = mutableListOf<FindroidSource>()
    sources.addAll(mediaSources?.map { it.toFindroidSource(context, id.toItemId()) } ?: emptyList())
    sources.addAll(localSources[id.toItemId()].orEmpty())
    return FindroidMovie(
        id = id.toItemId(),
        name = name.orEmpty(),
        originalTitle = originalTitle,
        overview = overview.orEmpty(),
        sources = sources,
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        canPlay = playAccess != PlayAccess.NONE,
        // CE (#671): the download store keeps movies and episodes only, and a home video or a
        // music video stored as a movie would be listed with the downloaded movies.
        canDownload = canDownload == true && type == BaseItemKind.MOVIE,
        runtimeTicks = runTimeTicks ?: 0,
        playbackPositionTicks = userData?.playbackPositionTicks ?: 0,
        premiereDate = premiereDate,
        communityRating = communityRating,
        genres = genres ?: emptyList(),
        people = people?.map { it.toFindroidPerson(context.baseUrl) } ?: emptyList(),
        officialRating = officialRating,
        status = status ?: "Ended",
        productionYear = productionYear,
        endDate = endDate,
        trailer = remoteTrailers?.getOrNull(0)?.url,
        lastPlayedDate = userData?.lastPlayedDate,
        trailers = remoteTrailers.toFindroidTrailers(),
        tagline = taglines.firstTagline(),
        criticRating = criticRating,
        studios = studioNames(),
        externalLinks = toExternalLinks(),
        playCount = userData?.playCount ?: 0,
        is3D = video3dFormat != null,
        localTrailerCount = localTrailerCount ?: 0,
        specialFeatureCount = specialFeatureCount,
        images = toFindroidImages(context.baseUrl),
        chapters = toFindroidChapters(),
        trickplayInfo = toFindroidTrickplayInfo(),
    )
}
