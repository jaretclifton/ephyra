package dev.jdtech.jellyfin.film.presentation.collection

import dev.jdtech.jellyfin.models.CollectionSection

data class CollectionState(
    val sections: List<CollectionSection> = emptyList(),
    val isLoading: Boolean = true,
    val error: Exception? = null,
    /**
     * CE (#775): [sections] came from a load made while the server was away, which succeeded: the
     * downloaded favourites only.
     */
    val downloadsOnly: Boolean = false,
)
