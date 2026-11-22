package dev.jdtech.jellyfin.film.presentation.collection

import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.UserId

data class CollectionState(
    val sections: List<CollectionSection> = emptyList(),
    val isLoading: Boolean = true,
    val error: Exception? = null,
    /**
     * CE (#775): [sections] came from a load made while the server was away, which succeeded: the
     * downloaded favourites only.
     */
    val downloadsOnly: Boolean = false,
    /**
     * CE (#866): the user [sections] are for, where the screen stays on its stack across a user
     * switch (Favourites); null before the first load, or when unknown.
     */
    val userId: UserId? = null,
) {
    /**
     * CE (#866): what the screen may show while [userId] is signed in: nothing of another user's
     * sections, only a loading list until the new user's arrive. Null [userId] (no session at hand,
     * as in previews) shows everything.
     */
    fun shownTo(userId: UserId?): CollectionState =
        if (userId == null || this.userId == null || this.userId == userId) {
            this
        } else {
            CollectionState(isLoading = true, userId = userId)
        }
}
