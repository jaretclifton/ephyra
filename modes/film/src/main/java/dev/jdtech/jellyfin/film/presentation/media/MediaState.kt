package dev.jdtech.jellyfin.film.presentation.media

import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.UserId

data class MediaState(
    val libraries: List<FindroidCollection> = emptyList(),
    val isLoading: Boolean = false,
    val error: Exception? = null,
    /** CE (#810): the user [libraries] are for; null before the first load, or when unknown. */
    val userId: UserId? = null,
) {
    /**
     * CE (#810): what the tab may show while [userId] is signed in: nothing of another user's
     * libraries, only a loading list until the new user's arrive. Null [userId] (no session at
     * hand, as in previews) shows everything.
     */
    fun shownTo(userId: UserId?): MediaState =
        if (userId == null || this.userId == null || this.userId == userId) {
            this
        } else {
            MediaState(isLoading = true, userId = userId)
        }
}
