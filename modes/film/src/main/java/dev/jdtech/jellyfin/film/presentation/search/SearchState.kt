package dev.jdtech.jellyfin.film.presentation.search

import dev.jdtech.jellyfin.models.FindroidItem

data class SearchState(
    /** The query [items] and [error] belong to. */
    val query: String = "",
    val items: List<FindroidItem> = emptyList(),
    val loading: Boolean = false,
    val error: Exception? = null,
    /** CE (#775): [items] came from a search made while the server was away: downloads only. */
    val downloadsOnly: Boolean = false,
)
