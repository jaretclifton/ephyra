package dev.jdtech.jellyfin.film.presentation.search

import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.UserId

data class SearchState(
    /** The query [items] and [error] belong to. */
    val query: String = "",
    val items: List<Item> = emptyList(),
    val loading: Boolean = false,
    val error: Exception? = null,
    /** CE (#775): [items] came from a search made while the server was away: downloads only. */
    val downloadsOnly: Boolean = false,
    /**
     * CE (#866): the user [items] are for. The search lives in the Media tab, which stays on its
     * stack across a user switch. Null before the first search, or when unknown.
     */
    val userId: UserId? = null,
) {
    /**
     * CE (#866): what the search may show while [userId] is signed in: none of another user's
     * results, only a search on its way until the new user's arrive. Null [userId] (no session at
     * hand, as in previews) shows everything.
     */
    fun shownTo(userId: UserId?): SearchState =
        if (userId == null || this.userId == null || this.userId == userId) {
            this
        } else {
            SearchState(loading = true, userId = userId)
        }
}
