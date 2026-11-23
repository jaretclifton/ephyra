package dev.jdtech.jellyfin.core.presentation.dummy

import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidSeason

val dummySeason =
    FindroidSeason(
        id = randomItemId(),
        name = "Season 1",
        seriesId = randomItemId(),
        seriesName = "Attack on Titan",
        originalTitle = null,
        overview = "",
        sources = emptyList(),
        indexNumber = 0,
        episodes = emptyList(),
        played = false,
        favorite = false,
        canPlay = true,
        canDownload = false,
        unplayedItemCount = null,
        images = FindroidImages(),
    )
