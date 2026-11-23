package dev.jdtech.jellyfin.server.jellyfin.mappers

import dev.jdtech.jellyfin.models.Season
import dev.jdtech.jellyfin.server.jellyfin.toItemId
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PlayAccess

internal fun BaseItemDto.toSeason(baseUrl: String): Season {
    return Season(
        id = id.toItemId(),
        name = name.orEmpty(),
        originalTitle = originalTitle,
        overview = overview.orEmpty(),
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        canPlay = playAccess != PlayAccess.NONE,
        canDownload = canDownload == true,
        unplayedItemCount = userData?.unplayedItemCount,
        indexNumber = indexNumber ?: 0,
        sources = emptyList(),
        episodes = emptyList(),
        seriesId = seriesId!!.toItemId(),
        seriesName = seriesName.orEmpty(),
        images = toItemImages(baseUrl),
    )
}
