package dev.jdtech.jellyfin.server.jellyfin.mappers

import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.firstTagline
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PlayAccess

internal fun BaseItemDto.toFindroidShow(baseUrl: String): FindroidShow {
    return FindroidShow(
        id = id,
        name = name.orEmpty(),
        originalTitle = originalTitle,
        overview = overview.orEmpty(),
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        canPlay = playAccess != PlayAccess.NONE,
        canDownload = canDownload == true,
        unplayedItemCount = userData?.unplayedItemCount,
        sources = emptyList(),
        seasons = emptyList(),
        genres = genres ?: emptyList(),
        people = people?.map { it.toFindroidPerson(baseUrl) } ?: emptyList(),
        runtimeTicks = runTimeTicks ?: 0,
        communityRating = communityRating,
        officialRating = officialRating,
        status = status ?: "Ended",
        productionYear = productionYear,
        endDate = endDate,
        trailer = remoteTrailers?.getOrNull(0)?.url,
        trailers = remoteTrailers.toFindroidTrailers(),
        tagline = taglines.firstTagline(),
        criticRating = criticRating,
        studios = studioNames(),
        externalLinks = toExternalLinks(),
        airDays = airDays.toDaysOfWeek(),
        airTime = airTime?.takeIf { it.isNotBlank() }?.trim(),
        localTrailerCount = localTrailerCount ?: 0,
        specialFeatureCount = specialFeatureCount,
        images = toFindroidImages(baseUrl),
    )
}
