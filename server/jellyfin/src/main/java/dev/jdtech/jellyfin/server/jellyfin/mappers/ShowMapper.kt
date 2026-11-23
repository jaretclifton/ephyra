package dev.jdtech.jellyfin.server.jellyfin.mappers

import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.firstTagline
import dev.jdtech.jellyfin.server.jellyfin.toItemId
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PlayAccess

internal fun BaseItemDto.toShow(baseUrl: String): Show {
    return Show(
        id = id.toItemId(),
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
        people = people?.map { it.toPerson(baseUrl) } ?: emptyList(),
        runtimeTicks = runTimeTicks ?: 0,
        communityRating = communityRating,
        officialRating = officialRating,
        status = status ?: "Ended",
        productionYear = productionYear,
        endDate = endDate,
        trailer = remoteTrailers?.getOrNull(0)?.url,
        trailers = remoteTrailers.toTrailers(),
        tagline = taglines.firstTagline(),
        criticRating = criticRating,
        studios = studioNames(),
        externalLinks = toExternalLinks(),
        airDays = airDays.toDaysOfWeek(),
        airTime = airTime?.takeIf { it.isNotBlank() }?.trim(),
        localTrailerCount = localTrailerCount ?: 0,
        specialFeatureCount = specialFeatureCount,
        images = toItemImages(baseUrl),
    )
}
