package dev.jdtech.jellyfin.film.presentation.home

import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.User
import java.util.UUID

data class HomeState(
    val server: Server? = null,
    val user: User? = null,
    val suggestionsSection: HomeItem.Suggestions? = null,
    val resumeSection: HomeItem.Section? = null,
    val nextUpSection: HomeItem.Section? = null,
    val views: List<HomeItem.ViewItem> = emptyList(),
    // CE (#142): see HomeRowsOrder.kt.
    val rowOrder: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val error: Exception? = null,
    /**
     * CE (#608): the cards of Continue Watching and Next Up show the episode's own image instead of
     * the series' or movie's thumb.
     */
    val useEpisodeImages: Boolean = false,
) {
    val isEmpty: Boolean
        get() =
            suggestionsSection == null &&
                resumeSection == null &&
                nextUpSection == null &&
                views.isEmpty()

    /**
     * CE (#810): what Home may show while [userId] is signed in. The client switches user before
     * Home is back on screen, and the ViewModel hears of it from Room a moment later: until then
     * the rows and avatar are the previous user's, so none of them shows, only the server and an
     * empty, loading Home. Null [userId] (no session at hand, as in previews) shows everything.
     */
    fun shownTo(userId: UUID?): HomeState =
        if (userId == null || user == null || user.id == userId) {
            this
        } else {
            HomeState(server = server, useEpisodeImages = useEpisodeImages)
        }
}
