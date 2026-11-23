package dev.jdtech.jellyfin.repository

import android.app.Application
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSourceEntity
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.util.Collections
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CE (#756): the catalog gives the server's sources only; the repository adds the downloads after
 * them, as the mappers did before, with one lookup of downloads for a whole list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CatalogLocalSourcesTest {
    private val movieId = "d10943c9-a35f-58d6-1542-dc562fdabcf2".toItemId()
    private val episodeId = "4e38ea74-3dcd-9230-7b5a-9b05e5d57312".toItemId()
    private val seriesId = "332758e9-c2da-4fb5-cf9f-7e1efffc9d37".toItemId()

    private val movie = StandInServer.resource("golden/movie.json")
    private val episode = StandInServer.resource("golden/episode.json")
    private val show = StandInServer.resource("golden/show.json")

    private fun page(vararg items: String) =
        """{"Items":[${items.joinToString(",")}],"TotalRecordCount":${items.size},"StartIndex":0}"""

    private val server = StandInServer { request ->
        when {
            request.path == "/Items" -> page(movie, show, episode)
            request.path.endsWith("/Episodes") -> page(episode)
            request.path.replace("-", "").contains(movieId.asString().replace("-", "")) -> movie
            else -> null
        }
    }

    /** The item ids of each lookup of downloads. */
    private val lookups: MutableList<List<ItemId>> = Collections.synchronizedList(mutableListOf())

    private val database =
        fakeProxy(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, args ->
            when (method.kotlinName) {
                "observeHasSources" -> flowOf(true)
                "getMovieOfServer",
                "getEpisodeOfServer" -> null
                "getSourcesForItemsOfServer" -> {
                    @Suppress("UNCHECKED_CAST") val ids = args!![0] as List<ItemId>
                    lookups += ids
                    ids.filter { it == movieId || it == episodeId }.map { localSource(it) }
                }
                "getMediaStreamsForSources" -> emptyList<Any>()
                else -> throw NotImplementedError("ServerDatabaseDao.${method.kotlinName}")
            }
        }
            as ServerDatabaseDao

    private fun localSource(itemId: ItemId) =
        ItemSourceEntity(
            id = "download-${itemId.asString()}",
            itemId = itemId,
            name = "Original",
            type = ItemSourceType.LOCAL,
            path = "/storage/downloads/${itemId.asString()}.mkv",
        )

    private val repository by lazy {
        val context = StandInServer.context()
        server.repository(
            context = context,
            database = database,
            preferences =
                StandInServer.preferences(context).apply { setValue(currentServer, "server") },
        )
    }

    @After
    fun stop() {
        server.close()
    }

    @Test
    fun `a movie has the server's sources first, then its download`() = runBlocking {
        val result = repository.getMovie(movieId)

        assertEquals(
            listOf(ItemSourceType.REMOTE, ItemSourceType.LOCAL),
            result.sources.map { it.type },
        )
        assertEquals("download-${movieId.asString()}", result.sources.last().id)
    }

    @Test
    fun `a list adds the downloads of its movies and episodes in one lookup`() = runBlocking {
        val items = repository.getItems()

        val movie = items.filterIsInstance<Movie>().single()
        val episode = items.filterIsInstance<Episode>().single()
        val show = items.filterIsInstance<Show>().single()
        assertEquals(
            listOf(ItemSourceType.REMOTE, ItemSourceType.LOCAL),
            movie.sources.map { it.type },
        )
        assertEquals(
            listOf(ItemSourceType.REMOTE, ItemSourceType.LOCAL),
            episode.sources.map { it.type },
        )
        assertEquals(emptyList(), show.sources)
        assertEquals(listOf(listOf(movieId, episodeId)), lookups)
    }

    @Test
    fun `the episodes of a show have the server's sources first, then their downloads`() =
        runBlocking {
            val result = repository.getEpisodes(seriesId, seasonId = null).single()

            assertEquals(
                listOf(ItemSourceType.REMOTE, ItemSourceType.LOCAL),
                result.sources.map { it.type },
            )
        }
}
