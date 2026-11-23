package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.EpisodeEntity
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemFilter
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.models.MovieEntity
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.ShowEntity
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.UserDataEntity
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.getLocalSourcesForItems
import dev.jdtech.jellyfin.models.localImagePath
import dev.jdtech.jellyfin.models.toEpisode
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMovie
import dev.jdtech.jellyfin.models.toShow
import java.time.LocalDateTime
import java.util.Locale

/**
 * CE (#775): the two libraries the Media tab shows while the server is away, holding the downloaded
 * movies and shows. Room does not know which server library a download came from. The server does
 * not know these ids, so whatever asks for their contents gets Room's answer.
 */
object StoredLibraries {
    val MOVIES: ItemId = "a1b2c3d4-0000-0000-0000-000000000001".toItemId()
    val SHOWS: ItemId = "a1b2c3d4-0000-0000-0000-000000000002".toItemId()

    fun isStored(id: ItemId?): Boolean = id == MOVIES || id == SHOWS
}

/** CE (#775): the names of [StoredLibraries], from the app's strings (the data module has none). */
fun interface StoredLibraryNames {
    fun nameOf(type: CollectionType): String

    companion object {
        val ENGLISH = StoredLibraryNames {
            if (it == CollectionType.Movies) "Movies" else "TV Shows"
        }
    }
}

/**
 * CE (#775): the lists Room answers while the server is away: downloaded favourites, search results
 * and [StoredLibraries]. Only the rows of the given server and the user data of the given user
 * (#718), and only what has a finished download: something that can play. The lists are what is
 * downloaded, so they are sorted and filtered in memory, on the rows before they are mapped.
 */
internal class StoredLists(private val database: ServerDatabaseDao) {
    suspend fun favorites(serverId: ServerId, userId: UserId): List<Any> =
        database.getFavoriteMoviesOfServer(serverId, userId) +
            database.getFavoriteShowsOfServer(serverId, userId) +
            database.getFavoriteEpisodesOfServer(serverId, userId)

    /** By name, as the server's search: movies and shows unless [kinds] says otherwise. */
    suspend fun search(
        serverId: ServerId,
        query: String,
        kinds: List<ItemKind>?,
        limitPerKind: Int,
    ): List<Any> {
        val pattern = "%" + query.trim().replace(LIKE_SPECIAL) { "\\" + it.value } + "%"
        val wanted = kinds ?: listOf(ItemKind.MOVIE, ItemKind.SERIES)
        return buildList {
            if (ItemKind.MOVIE in wanted) {
                addAll(database.searchMoviesOfServer(serverId, pattern).take(limitPerKind))
            }
            if (ItemKind.SERIES in wanted) {
                addAll(database.searchShowsOfServer(serverId, pattern).take(limitPerKind))
            }
            if (ItemKind.EPISODE in wanted) {
                addAll(database.searchEpisodesOfServer(serverId, pattern).take(limitPerKind))
            }
        }
    }

    /** Whether anything of the server is downloaded (finished), favourite or not. */
    suspend fun hasDownloads(serverId: ServerId): Boolean =
        database.countDownloadedMoviesOfServer(serverId) > 0 ||
            database.countDownloadedShowsOfServer(serverId) > 0

    /**
     * Each of [StoredLibraries] that holds something. CE (#781): with the artwork of the most
     * recently downloaded of its items that has some stored ([storedImage]: whether the file of
     * that image was saved with the download), a backdrop before a poster, as the wide card of a
     * real library shows. Without any, the blank card as before.
     */
    suspend fun libraries(
        serverId: ServerId,
        names: StoredLibraryNames,
        storedImage: (itemId: ItemId, image: String) -> Boolean = { _, _ -> false },
    ): List<Library> {
        val movies = database.getDownloadedMoviesOfServer(serverId).map { it.id }
        val shows = database.getDownloadedShowsOfServer(serverId).map { it.id }
        if (movies.isEmpty() && shows.isEmpty()) return emptyList()
        val downloaded =
            database.getDownloadDatesOfServer(serverId).associate { it.itemId to it.date }
        fun artwork(ids: List<ItemId>): ItemImages {
            val newestFirst = ids.sortedByDescending { downloaded[it] ?: Long.MIN_VALUE }
            val image = STORED_IMAGES.firstNotNullOfOrNull { image ->
                newestFirst
                    .firstOrNull { storedImage(it, image) }
                    ?.let { localImagePath(it, image) }
            }
            return ItemImages(primary = image)
        }
        return buildList {
            if (movies.isNotEmpty()) {
                add(library(StoredLibraries.MOVIES, CollectionType.Movies, names, artwork(movies)))
            }
            if (shows.isNotEmpty()) {
                add(library(StoredLibraries.SHOWS, CollectionType.TvShows, names, artwork(shows)))
            }
        }
    }

    private fun library(
        id: ItemId,
        type: CollectionType,
        names: StoredLibraryNames,
        images: ItemImages,
    ) = Library(id = id, name = names.nameOf(type), type = type, images = images)

    /**
     * The rows of [libraryId], sorted as the server would. Filters Room cannot answer are left out
     * rather than failing the grid; so are sorts it has no data for (critic rating), which fall
     * back to the name.
     */
    suspend fun libraryContents(
        serverId: ServerId,
        userId: UserId,
        libraryId: ItemId,
        sortBy: SortBy,
        sortOrder: SortOrder,
        filters: Set<ItemFilter>,
    ): List<Any> =
        sorted(entries(serverId, userId, libraryId, filters), serverId, userId, sortBy)
            .let { if (sortOrder == SortOrder.DESCENDING) it.asReversed() else it }
            .map { it.dto }

    /** How many rows of [libraryId] sort before [nameLessThan], for the A–Z bar. */
    suspend fun libraryCount(
        serverId: ServerId,
        userId: UserId,
        libraryId: ItemId,
        filters: Set<ItemFilter>,
        nameLessThan: String?,
    ): Int {
        val entries = entries(serverId, userId, libraryId, filters)
        if (nameLessThan == null) return entries.size
        val boundary = sortName(nameLessThan)
        return entries.count { it.sortName < boundary }
    }

    suspend fun toItems(dtos: List<Any>, serverId: ServerId, userId: UserId): List<Item> {
        val local =
            database.getLocalSourcesForItems(
                dtos.mapNotNull {
                    when (it) {
                        is MovieEntity -> it.id
                        is EpisodeEntity -> it.id
                        else -> null
                    }
                },
                serverId,
            )
        return dtos.map {
            when (it) {
                is MovieEntity -> it.toMovie(database, userId, local)
                is ShowEntity -> it.toShow(database, userId)
                is EpisodeEntity -> it.toEpisode(database, userId, local)
                else -> error("Not a stored item: $it")
            }
        }
    }

    /** A row with what sorting and filtering read. */
    private class Entry(
        val dto: Any,
        val id: ItemId,
        name: String,
        val rating: Float?,
        val released: LocalDateTime?,
        val userData: UserDataEntity?,
    ) {
        val sortName = sortName(name)
    }

    private suspend fun entries(
        serverId: ServerId,
        userId: UserId,
        libraryId: ItemId,
        filters: Set<ItemFilter>,
    ): List<Entry> {
        val userData = database.getUserDataOfUser(userId).associateBy { it.itemId }
        val entries =
            if (libraryId == StoredLibraries.MOVIES) {
                database.getDownloadedMoviesOfServer(serverId).map {
                    Entry(it, it.id, it.name, it.communityRating, it.premiereDate, userData[it.id])
                }
            } else {
                database.getDownloadedShowsOfServer(serverId).map {
                    Entry(
                        it,
                        it.id,
                        it.name,
                        it.communityRating,
                        it.productionYear?.let { year -> LocalDateTime.of(year, 1, 1, 0, 0) },
                        userData[it.id],
                    )
                }
            }
        if (filters.isEmpty()) return entries
        return entries.filter { entry -> filters.all { entry.userData.matches(it) } }
    }

    private fun UserDataEntity?.matches(filter: ItemFilter): Boolean =
        when (filter) {
            ItemFilter.IS_UNPLAYED -> this?.played != true
        }

    /** Ascending; a missing value comes first, as on the server. Ties go by name. */
    private suspend fun sorted(
        entries: List<Entry>,
        serverId: ServerId,
        userId: UserId,
        sortBy: SortBy,
    ): List<Entry> {
        val byName = compareBy<Entry>({ it.sortName }, { it.id.value })
        val comparator =
            when (sortBy) {
                SortBy.IMDB_RATING -> compareBy<Entry> { it.rating }
                SortBy.RELEASE_DATE -> compareBy { it.released }
                SortBy.DATE_ADDED -> {
                    val dates =
                        database.getDownloadDatesOfServer(serverId).associate {
                            it.itemId to it.date
                        }
                    compareBy { dates[it.id] }
                }
                SortBy.DATE_PLAYED,
                SortBy.SERIES_DATE_PLAYED -> {
                    // A show's own row has no date: the last of its episodes played is its date.
                    val shows =
                        database.getShowPlayedDatesOfServer(serverId, userId).associate {
                            it.itemId to it.date
                        }
                    compareBy { shows[it.id] ?: it.userData?.lastPlayedDate }
                }
                SortBy.NAME,
                SortBy.PARENTAL_RATING -> byName
            }
        return entries.sortedWith(comparator.then(byName))
    }

    private companion object {
        val LIKE_SPECIAL = Regex("""[\\%_]""")

        /** Case does not count, as with the server's sort names. */
        fun sortName(name: String) = name.lowercase(Locale.ROOT)
    }
}

/**
 * CE (#781): the images a download stores (ImagesDownloaderWorker), in the order a stored library
 * card takes them: the wide backdrop, then the poster.
 */
private val STORED_IMAGES = listOf("backdrop", "primary")
