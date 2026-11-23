package dev.jdtech.jellyfin.ce.presentation.screensaver

import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.repository.MediaRepository
import kotlinx.coroutines.CancellationException
import timber.log.Timber

internal const val SCREENSAVER_SOURCE_RECENTLY_ADDED = "recently_added"

private val screensaverKinds = listOf(ItemKind.MOVIE, ItemKind.SERIES)
private const val SCREENSAVER_LIMIT = 100

/**
 * CE (#140): the items the screensaver cycles through, all with a backdrop. "Random" lets the
 * server pick from the whole library (sorting by name and shuffling would only ever show the start
 * of the alphabet). "Recently added" keeps the newest first. Empty when loading fails.
 */
internal suspend fun loadScreensaverItems(repository: MediaRepository, source: String): List<Item> =
    try {
        if (source == SCREENSAVER_SOURCE_RECENTLY_ADDED) {
            repository
                .getItems(
                    includeTypes = screensaverKinds,
                    recursive = true,
                    sortBy = SortBy.DATE_ADDED,
                    sortOrder = SortOrder.DESCENDING,
                    limit = SCREENSAVER_LIMIT,
                )
                .filter { it.images.backdrop != null }
        } else {
            repository.getRandomItemsWithBackdrop(screensaverKinds, SCREENSAVER_LIMIT)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Screensaver: failed to load library artwork")
        emptyList()
    }
