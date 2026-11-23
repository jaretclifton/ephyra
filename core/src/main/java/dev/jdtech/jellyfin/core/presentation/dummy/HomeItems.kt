package dev.jdtech.jellyfin.core.presentation.dummy

import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.HomeSection
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.View

val dummyHomeSuggestions = HomeItem.Suggestions(id = randomItemId(), items = dummyMovies)

val dummyHomeSection =
    HomeItem.Section(
        HomeSection(
            id = randomItemId(),
            name = UiText.DynamicString("Continue watching"),
            items = dummyMovies + dummyEpisodes,
        )
    )

val dummyHomeView =
    HomeItem.ViewItem(
        View(
            id = randomItemId(),
            name = "Movies",
            items = dummyMovies,
            type = CollectionType.Movies,
        )
    )
