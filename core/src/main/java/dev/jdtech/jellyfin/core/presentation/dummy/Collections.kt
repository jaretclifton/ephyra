package dev.jdtech.jellyfin.core.presentation.dummy

import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidImages

private val dummyMoviesCollection =
    FindroidCollection(
        id = randomItemId(),
        name = "Movies",
        type = CollectionType.Movies,
        images = FindroidImages(),
    )

private val dummyShowsCollection =
    FindroidCollection(
        id = randomItemId(),
        name = "Shows",
        type = CollectionType.TvShows,
        images = FindroidImages(),
    )

val dummyCollections = listOf(dummyMoviesCollection, dummyShowsCollection)
