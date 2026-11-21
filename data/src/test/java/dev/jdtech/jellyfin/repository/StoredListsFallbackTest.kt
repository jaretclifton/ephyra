package dev.jdtech.jellyfin.repository

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.paging.PagingSource
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.FindroidEpisodeDto
import dev.jdtech.jellyfin.models.FindroidMovieDto
import dev.jdtech.jellyfin.models.FindroidSeasonDto
import dev.jdtech.jellyfin.models.FindroidShowDto
import dev.jdtech.jellyfin.models.FindroidSourceDto
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.FindroidUserDataDto
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.player.DeviceProfileBuilder
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#775): the list getters answer from Room while the server is away: the downloaded favourites,
 * search results and the stored Movies and Shows libraries, of the current server and user only.
 * The server here always fails: the API client has no address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoredListsFallbackTest {
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val otherUserId = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val showId = UUID.fromString("00000000-0000-0000-0000-000000000020")
    private val seasonId = UUID.fromString("00000000-0000-0000-0000-000000000021")
    private val episodeId = UUID.fromString("00000000-0000-0000-0000-000000000022")

    private val context: Context = RuntimeEnvironment.getApplication()
    private val room =
        Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val database: ServerDatabaseDao = room.getServerDatabaseDao()
    private val preferences =
        AppPreferences(context.getSharedPreferences("stored-lists", Context.MODE_PRIVATE)).apply {
            setValue(currentServer, SERVER_A)
        }

    private val knownServerState =
        object : KnownServerState {
            var unreachable = false
            var skipped = 0

            override fun isKnownUnreachable() = unreachable

            override fun onServerSkipped() {
                skipped++
            }
        }

    private var apiUserId = userId

    private val repository: JellyfinRepository by lazy {
        // The SDK names the device after it; Robolectric has none.
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "test")
        val api = JellyfinApi(context).apply { userId = this@StoredListsFallbackTest.apiUserId }
        JellyfinRepositoryImpl(
            context,
            api,
            database,
            preferences,
            DeviceProfileBuilder(context),
            knownServerState = knownServerState,
        )
    }

    @After fun close() = room.close()

    private fun id(n: Int) = UUID.fromString("00000000-0000-0000-0000-0000000001%02d".format(n))

    private suspend fun storeMovie(
        id: UUID,
        name: String,
        serverId: String = SERVER_A,
        rating: Float? = null,
        premiere: LocalDateTime? = null,
        downloadedAt: Long? = null,
    ) {
        database.insertMovie(
            FindroidMovieDto(
                id = id,
                serverId = serverId,
                name = name,
                originalTitle = null,
                overview = "",
                runtimeTicks = 0L,
                premiereDate = premiere,
                communityRating = rating,
                officialRating = null,
                status = "",
                productionYear = premiere?.year,
                endDate = null,
                chapters = null,
            )
        )
        database.insertSource(source(id, downloadedAt))
    }

    private suspend fun storeEpisode(
        serverId: String = SERVER_A,
        name: String = "Obstacle Course",
    ) {
        database.insertShow(
            FindroidShowDto(
                id = showId,
                serverId = serverId,
                name = "Bluey",
                originalTitle = null,
                overview = "",
                runtimeTicks = 0L,
                communityRating = null,
                officialRating = null,
                status = "Continuing",
                productionYear = 2018,
                endDate = null,
            )
        )
        database.insertSeason(
            FindroidSeasonDto(
                id = seasonId,
                seriesId = showId,
                name = "Season 1",
                seriesName = "Bluey",
                overview = "",
                indexNumber = 1,
            )
        )
        database.insertEpisode(
            FindroidEpisodeDto(
                id = episodeId,
                serverId = serverId,
                seasonId = seasonId,
                seriesId = showId,
                name = name,
                seriesName = "Bluey",
                overview = "",
                indexNumber = 1,
                indexNumberEnd = null,
                parentIndexNumber = 1,
                runtimeTicks = 4_200_000_000L,
                premiereDate = null,
                communityRating = null,
                chapters = null,
            )
        )
        database.insertSource(source(episodeId, null))
    }

    private suspend fun userData(
        itemId: UUID,
        user: UUID = userId,
        favorite: Boolean = false,
        played: Boolean = false,
        lastPlayed: Long? = null,
    ) =
        database.insertUserData(
            FindroidUserDataDto(
                userId = user,
                itemId = itemId,
                played = played,
                favorite = favorite,
                playbackPositionTicks = 0L,
                lastPlayedDate = lastPlayed,
            )
        )

    private fun source(itemId: UUID, downloadedAt: Long?) =
        FindroidSourceDto(
            id = "source-$itemId",
            itemId = itemId,
            name = "Original",
            type = FindroidSourceType.LOCAL,
            path = "/storage/downloads/$itemId.mkv",
            downloadedAt = downloadedAt,
        )

    private suspend fun fails(get: suspend () -> Any?): Boolean = runCatching { get() }.isFailure

    private suspend fun page(
        library: UUID,
        sortBy: SortBy = SortBy.NAME,
        sortOrder: SortOrder = SortOrder.ASCENDING,
        filters: List<ItemFilter>? = null,
        startIndex: Int? = null,
        limit: Int? = null,
    ) =
        repository.getItemsPage(
            parentId = library,
            sortBy = sortBy,
            sortOrder = sortOrder,
            filters = filters,
            startIndex = startIndex,
            limit = limit,
            includeTotal = true,
        )

    @Test
    fun `favourites are the downloaded ones of the current server and user`() = runBlocking {
        storeMovie(id(1), "Favourite")
        storeMovie(id(2), "Not a favourite")
        storeMovie(id(3), "Another user's favourite")
        storeMovie(id(4), "Another server's favourite", serverId = SERVER_B)
        storeEpisode()
        userData(id(1), favorite = true)
        userData(id(2))
        userData(id(3), user = otherUserId, favorite = true)
        userData(id(4), favorite = true)
        userData(showId, favorite = true)
        userData(episodeId, favorite = true)

        assertEquals(listOf(id(1), showId, episodeId), repository.getFavoriteItems().map { it.id })
        assertTrue(repository.getFavoriteItems().all { it.favorite })
    }

    @Test
    fun `without stored favourites the server's error surfaces`() = runBlocking {
        storeMovie(id(1), "Not a favourite")
        userData(id(1))
        assertTrue(fails { repository.getFavoriteItems() })
    }

    @Test
    fun `the stored libraries are those with downloads of the current server`() = runBlocking {
        assertTrue(fails { repository.getLibraries() }, "nothing stored")

        storeMovie(id(1), "Movie", serverId = SERVER_B)
        assertTrue(fails { repository.getLibraries() }, "only another server's")

        storeMovie(id(2), "Movie")
        assertEquals(
            listOf(StoredLibraries.MOVIES to CollectionType.Movies),
            repository.getLibraries().map { it.id to it.type },
        )

        storeEpisode()
        val libraries = repository.getLibraries()
        assertEquals(listOf(StoredLibraries.MOVIES, StoredLibraries.SHOWS), libraries.map { it.id })
        assertEquals(listOf("Movies", "TV Shows"), libraries.map { it.name })
    }

    @Test
    fun `a stored library sorts and pages its downloads`() = runBlocking {
        storeMovie(id(1), "banana", rating = 7f, premiere = LocalDateTime.of(2001, 1, 1, 0, 0))
        storeMovie(id(2), "Apple", rating = 9f, premiere = LocalDateTime.of(1999, 1, 1, 0, 0))
        storeMovie(id(3), "cherry", rating = null, premiere = LocalDateTime.of(2010, 1, 1, 0, 0))
        storeMovie(id(4), "Elsewhere", serverId = SERVER_B)

        val byName = page(StoredLibraries.MOVIES)
        assertEquals(listOf("Apple", "banana", "cherry"), byName.items.map { it.name })
        assertEquals(3, byName.totalRecordCount)

        assertEquals(
            listOf("cherry", "banana", "Apple"),
            page(StoredLibraries.MOVIES, sortOrder = SortOrder.DESCENDING).items.map { it.name },
        )
        assertEquals(
            listOf("cherry", "banana", "Apple"),
            page(StoredLibraries.MOVIES, sortBy = SortBy.IMDB_RATING).items.map { it.name },
        )
        assertEquals(
            listOf("Apple", "banana", "cherry"),
            page(StoredLibraries.MOVIES, sortBy = SortBy.RELEASE_DATE).items.map { it.name },
        )

        val second = page(StoredLibraries.MOVIES, startIndex = 1, limit = 1)
        assertEquals(listOf("banana"), second.items.map { it.name })
        assertEquals(3, second.totalRecordCount)
    }

    @Test
    fun `a stored library sorts by download and play dates`() = runBlocking {
        storeMovie(id(1), "Old download", downloadedAt = 1_000L)
        storeMovie(id(2), "New download", downloadedAt = 3_000L)
        storeMovie(id(3), "Middle download", downloadedAt = 2_000L)
        userData(id(1), lastPlayed = 30L)
        userData(id(2), lastPlayed = 10L)
        userData(id(3), lastPlayed = 20L)
        userData(id(3), user = otherUserId, lastPlayed = 99L)

        assertEquals(
            listOf("New download", "Middle download", "Old download"),
            page(StoredLibraries.MOVIES, SortBy.DATE_ADDED, SortOrder.DESCENDING).items.map {
                it.name
            },
        )
        assertEquals(
            listOf("New download", "Middle download", "Old download"),
            page(StoredLibraries.MOVIES, SortBy.DATE_PLAYED).items.map { it.name },
        )
    }

    @Test
    fun `a stored show sorts by the last of its episodes played`() = runBlocking {
        storeEpisode()
        userData(episodeId, lastPlayed = 50L)
        userData(showId)

        val shows = page(StoredLibraries.SHOWS, SortBy.SERIES_DATE_PLAYED)
        assertEquals(listOf(showId), shows.items.map { it.id })
    }

    @Test
    fun `a stored library filters on the current user's played state`() = runBlocking {
        storeMovie(id(1), "Seen")
        storeMovie(id(2), "Unseen")
        storeMovie(id(3), "Seen by someone else")
        userData(id(1), played = true)
        userData(id(3), user = otherUserId, played = true)

        val unplayed = page(StoredLibraries.MOVIES, filters = listOf(ItemFilter.IS_UNPLAYED))
        assertEquals(listOf("Seen by someone else", "Unseen"), unplayed.items.map { it.name })
        assertEquals(2, unplayed.totalRecordCount)
        assertEquals(
            2,
            repository.getItemCount(
                parentId = StoredLibraries.MOVIES,
                filters = listOf(ItemFilter.IS_UNPLAYED),
            ),
        )
    }

    @Test
    fun `the letter counts of a stored library match its name order`() = runBlocking {
        storeMovie(id(1), "12 Angry Men")
        storeMovie(id(2), "Amélie")
        storeMovie(id(3), "brave")
        storeMovie(id(4), "Coco")

        assertEquals(
            1,
            repository.getItemCount(parentId = StoredLibraries.MOVIES, nameLessThan = "a"),
        )
        assertEquals(
            2,
            repository.getItemCount(parentId = StoredLibraries.MOVIES, nameLessThan = "b"),
        )
        assertEquals(
            3,
            repository.getItemCount(parentId = StoredLibraries.MOVIES, nameLessThan = "c"),
        )
        assertEquals(4, repository.getItemCount(parentId = StoredLibraries.MOVIES))
    }

    @Test
    fun `the paging sources read a stored library`() = runBlocking {
        storeMovie(id(1), "Bingo")
        storeMovie(id(2), "Bandit")
        val refresh = PagingSource.LoadParams.Refresh<Int>(null, 40, false)

        // What getItemsPagingIndexed builds.
        var total: Int? = null
        val indexed =
            IndexedItemsPagingSource(
                    load = { start, limit, includeTotal ->
                        repository.getItemsPage(
                            parentId = StoredLibraries.MOVIES,
                            startIndex = start,
                            limit = limit,
                            includeTotal = includeTotal,
                        )
                    },
                    knownTotal = { total },
                    onTotal = { total = it },
                )
                .load(refresh)
        assertTrue(indexed is PagingSource.LoadResult.Page, "$indexed")
        assertEquals(listOf("Bandit", "Bingo"), indexed.data.map { it.name })
        assertEquals(0, indexed.itemsAfter)
        assertEquals(2, total)

        // What getItemsPaging builds.
        val plain =
            ItemsPagingSource(
                    repository,
                    StoredLibraries.MOVIES,
                    null,
                    false,
                    SortBy.NAME,
                    SortOrder.DESCENDING,
                    null,
                )
                .load(refresh)
        assertTrue(plain is PagingSource.LoadResult.Page, "$plain")
        assertEquals(listOf("Bingo", "Bandit"), plain.data.map { it.name })
    }

    @Test
    fun `a real library still asks the server`() = runBlocking {
        storeMovie(id(1), "Movie")
        assertTrue(fails { page(UUID.randomUUID()) })
        assertTrue(fails { repository.getItemCount(parentId = UUID.randomUUID()) })
    }

    @Test
    fun `search finds the downloads of the asked kinds by name`() = runBlocking {
        storeMovie(id(1), "Bluey: The Movie")
        storeMovie(id(2), "Bluey elsewhere", serverId = SERVER_B)
        storeMovie(id(3), "Cars")
        storeEpisode(name = "Bluey's Birthday")

        assertEquals(listOf(id(1), showId), repository.getSearchItems("bluey", null).map { it.id })
        assertEquals(
            listOf(episodeId),
            repository.getSearchItems("bluey", listOf(BaseItemKind.EPISODE)).map { it.id },
        )
        assertEquals(
            listOf(id(1)),
            repository.getSearchItems("BLUEY", listOf(BaseItemKind.MOVIE)).map { it.id },
        )
        // LIKE's wildcards are taken literally.
        assertTrue(fails { repository.getSearchItems("%", null) })
        assertTrue(fails { repository.getSearchItems("nothing", null) })
    }

    @Test
    fun `a server known to be away is skipped and asked about`() = runBlocking {
        storeMovie(id(1), "Movie")
        userData(id(1), favorite = true)
        knownServerState.unreachable = true

        assertEquals(listOf(id(1)), repository.getFavoriteItems().map { it.id })
        assertEquals(listOf(StoredLibraries.MOVIES), repository.getLibraries().map { it.id })
        assertEquals(listOf(id(1)), repository.getSearchItems("mov", null).map { it.id })
        assertEquals(3, knownServerState.skipped)

        // Nothing stored: the server is asked anyway, and its error surfaces.
        assertTrue(fails { repository.getSearchItems("nothing", null) })
        assertEquals(3, knownServerState.skipped)
    }

    private companion object {
        const val SERVER_A = "server-a"
        const val SERVER_B = "server-b"
    }
}
