package dev.jdtech.jellyfin.server.jellyfin.mappers

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.firstTagline
import dev.jdtech.jellyfin.server.jellyfin.toItemId
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.PlayAccess

/**
 * For lists: [localSources] comes from one `getLocalSourcesForItems` call (in `data`) for all
 * items.
 */
internal suspend fun BaseItemDto.toMovie(
    context: MapperContext,
    localSources: Map<ItemId, List<ItemSource>>,
): Movie {
    val sources = mutableListOf<ItemSource>()
    sources.addAll(mediaSources?.map { it.toItemSource(context, id.toItemId()) } ?: emptyList())
    sources.addAll(localSources[id.toItemId()].orEmpty())
    return Movie(
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
        people = people?.map { it.toPerson(context.baseUrl) } ?: emptyList(),
        officialRating = officialRating,
        status = status ?: "Ended",
        productionYear = productionYear,
        endDate = endDate,
        trailer = remoteTrailers?.getOrNull(0)?.url,
        lastPlayedDate = userData?.lastPlayedDate,
        trailers = remoteTrailers.toTrailers(),
        tagline = taglines.firstTagline(),
        criticRating = criticRating,
        studios = studioNames(),
        externalLinks = toExternalLinks(),
        playCount = userData?.playCount ?: 0,
        is3D = video3dFormat != null,
        localTrailerCount = localTrailerCount ?: 0,
        specialFeatureCount = specialFeatureCount,
        externalIds = providerIds.toExternalIds(),
        images = toItemImages(context.baseUrl),
        chapters = toChapters(),
        trickplayInfo = toItemTrickplayInfo(),
    )
}
