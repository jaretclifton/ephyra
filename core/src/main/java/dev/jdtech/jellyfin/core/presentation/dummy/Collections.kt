package dev.jdtech.jellyfin.core.presentation.dummy

import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.Library

private val dummyMoviesCollection =
    Library(
        id = randomItemId(),
        name = "Movies",
        type = CollectionType.Movies,
        images = ItemImages(),
    )

private val dummyShowsCollection =
    Library(
        id = randomItemId(),
        name = "Shows",
        type = CollectionType.TvShows,
        images = ItemImages(),
    )

val dummyCollections = listOf(dummyMoviesCollection, dummyShowsCollection)
