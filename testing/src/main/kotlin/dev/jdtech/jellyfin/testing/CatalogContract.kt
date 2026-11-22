package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.domain.ports.CatalogPort
import dev.jdtech.jellyfin.domain.ports.ItemQuery
import dev.jdtech.jellyfin.models.FindroidBoxSet
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.testing.ContractLibrary.Part
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The contract of [CatalogPort] (#771): what every implementation does with [ContractLibrary]. A
 * subclass says how to build the implementation over that library; the tests are written once.
 *
 * The tests assert on the kind of outcome, not on one server's quirks (the order of equal names,
 * the status of a malformed id), so a server of another type can pass them.
 */
abstract class CatalogContract {
    /** A catalog that presents [ContractLibrary]. */
    abstract fun catalog(): CatalogPort

    /** A catalog whose server answers every request with an error status. */
    abstract fun failingCatalog(): CatalogPort

    /** What the server of [catalog] can do. */
    open val capabilities: ServerCapabilities = ServerCapabilities.ALL

    /** The parts of the library the server of [catalog] does not hold; their tests are skipped. */
    open val notOnServer: Set<Part> = emptySet()

    private fun assumeOnServer(part: Part) =
        assumeTrue("the server has no ${part.name.lowercase()}", part !in notOnServer)

    private val library = ContractLibrary

    @Test
    fun `a movie has its name, runtime and resume position`() = runTest {
        val expected = library.movieWithResume
        val movie = catalog().movie(expected.id)

        assertEquals(expected.id, movie.id)
        assertEquals(expected.name, movie.name)
        assertEquals(expected.runtimeTicks, movie.runtimeTicks)
        assertEquals(expected.playbackPositionTicks, movie.playbackPositionTicks)
        assertFalse(movie.played)
    }

    @Test
    fun `item gives each item as its kind`() = runTest {
        val catalog = catalog()

        assertIs<FindroidMovie>(catalog.item(library.favoriteMovie.id))
        assertIs<FindroidShow>(catalog.item(library.series.id))
        val episode = assertIs<FindroidEpisode>(catalog.item(library.nextUp.id))
        assertEquals(library.season1.id, episode.seasonId)
        assertEquals(library.series.id, episode.seriesId)
    }

    @Test
    fun `a box set is a box set`() = runTest {
        assumeOnServer(Part.BOX_SET)
        val boxSet = assertIs<FindroidBoxSet>(catalog().item(library.boxSet.id))

        assertEquals(library.boxSet.name, boxSet.name)
    }

    @Test
    fun `a person has a name`() = runTest {
        assumeOnServer(Part.PERSON)
        val person = catalog().person(library.person.id)

        assertEquals(library.person.id, person.id)
        assertEquals(library.person.name, person.name)
    }

    @Test
    fun `the favourite movie is a favourite`() = runTest {
        val catalog = catalog()

        assertTrue(catalog.movie(library.favoriteMovie.id).favorite)
        val favorites = catalog.favorites().map { it.id }
        assertTrue(library.favoriteMovie.id in favorites, "favorites: $favorites")
        assertFalse(library.movieWithResume.id in favorites, "favorites: $favorites")
    }

    @Test
    fun `the resumed movie is among the items to resume`() = runTest {
        val resume = catalog().resumeItems().map { it.id }

        assertTrue(library.movieWithResume.id in resume, "resume items: $resume")
        assertFalse(library.favoriteMovie.id in resume, "resume items: $resume")
    }

    @Test
    fun `items of a kind are only that kind`() = runTest {
        val catalog = catalog()
        val movies =
            catalog.items(ItemQuery(kinds = listOf(ItemKind.MOVIE), recursive = true, limit = 20))
        val shows =
            catalog.items(ItemQuery(kinds = listOf(ItemKind.SERIES), recursive = true, limit = 20))

        assertTrue(movies.items.isNotEmpty())
        assertTrue(movies.items.all { it is FindroidMovie }, "movies: ${movies.items}")
        assertTrue(shows.items.isNotEmpty())
        assertTrue(shows.items.all { it is FindroidShow }, "shows: ${shows.items}")
    }

    @Test
    fun `a page reports the total only when asked`() = runTest {
        val catalog = catalog()
        val query = ItemQuery(parentId = library.season1.id, kinds = listOf(ItemKind.EPISODE))

        assertNull(catalog.items(query).totalRecordCount)
        val counted = catalog.items(query.copy(limit = 2), includeTotal = true)
        assertEquals(library.season1Episodes.size, counted.totalRecordCount)
        assertEquals(2, counted.items.size)
        assertEquals(library.season1Episodes.size, catalog.itemCount(query.copy(limit = 2)))
    }

    @Test
    fun `start index and limit page through the items`() = runTest {
        val catalog = catalog()
        val query = ItemQuery(parentId = library.season1.id, kinds = listOf(ItemKind.EPISODE))
        val all = catalog.items(query).items.map { it.id }
        val page = catalog.items(query.copy(startIndex = 1, limit = 2)).items.map { it.id }

        assertEquals(library.season1Episodes.map { it.id }.toSet(), all.toSet())
        assertEquals(all.drop(1).take(2), page)
    }

    @Test
    fun `the seasons of a series come in their order`() = runTest {
        val seasons = catalog().seasons(library.series.id)

        assertEquals(library.seasons.map { it.id }, seasons.map { it.id })
        assertEquals(library.seasons.map { it.indexNumber }, seasons.map { it.indexNumber })
    }

    @Test
    fun `episodes of a season come in episode order`() = runTest {
        val episodes =
            catalog()
                .episodes(
                    library.series.id,
                    library.season1.id,
                    fields = null,
                    startItemId = null,
                    limit = null,
                )

        assertEquals(library.season1Episodes.map { it.id }, episodes.map { it.id })
        assertEquals((1..library.season1Episodes.size).toList(), episodes.map { it.indexNumber })
        assertTrue(episodes.all { it.seasonId == library.season1.id })
        assertEquals(listOf(true, false, false, false, false), episodes.map { it.played })
    }

    @Test
    fun `next up is the first unplayed episode`() = runTest {
        val nextUp = catalog().nextUp(library.series.id, dateCutoff = null)

        assertEquals(library.nextUp.id, nextUp.firstOrNull()?.id, "next up: $nextUp")
    }

    @Test
    fun `search finds by a word of the title`() = runTest {
        val found = catalog().search("Versions", kinds = null)

        assertTrue(library.movieWithTwoVersions.id in found.map { it.id }, "found: $found")
        assertTrue(found.all { it is FindroidMovie || it is FindroidShow }, "found: $found")
    }

    @Test
    fun `search of a kind finds only that kind`() = runTest {
        val found = catalog().search("Test Show", kinds = listOf(ItemKind.SERIES))

        assertTrue(library.series.id in found.map { it.id }, "found: $found")
        assertTrue(found.all { it is FindroidShow }, "found: $found")
    }

    @Test
    fun `the libraries are there`() = runTest {
        val catalog = catalog()

        assertTrue(catalog.libraries().isNotEmpty())
        assertTrue(catalog.userViews().isNotEmpty())
        assertEquals(library.server.id, catalog.serverInfo().id)
    }

    @Test
    fun `an unknown id fails with an error status from item and from movie`() = runTest {
        val catalog = catalog()

        val fromItem = assertFailsWith<ServerException> { catalog.item(library.unknownId) }
        assertIs<ServerException.HttpStatus>(fromItem)
        val fromMovie = assertFailsWith<ServerException> { catalog.movie(library.unknownId) }
        assertIs<ServerException.HttpStatus>(fromMovie)
        assertTrue(fromMovie.status in 400..499, "status ${fromMovie.status}")
    }

    @Test
    fun `a failing server gives ServerException, never another exception type`() = runTest {
        val catalog = failingCatalog()
        val calls: List<suspend () -> Any?> =
            listOf(
                { catalog.item(library.movieWithResume.id) },
                { catalog.movie(library.movieWithResume.id) },
                { catalog.items(ItemQuery(parentId = library.season1.id)) },
                { catalog.search("Versions", kinds = null) },
                { catalog.seasons(library.series.id) },
                { catalog.nextUp(library.series.id, dateCutoff = null) },
                { catalog.libraries() },
            )

        for (call in calls) {
            val error = assertFailsWith<ServerException> { call() }
            assertIs<ServerException.HttpStatus>(error)
        }
    }

    @Test
    fun `a server without theme songs gives none`() = runTest {
        assumeFalse(capabilities.themeSongs)
        assertNull(catalog().themeSongUrl(library.series.id))
    }

    @Test
    fun `a server without similar items gives none`() = runTest {
        assumeFalse(capabilities.similarItems)
        assertTrue(catalog().similar(library.movieWithResume.id, limit = 12).isEmpty())
    }

    @Test
    fun `a server without extras gives none`() = runTest {
        assumeFalse(capabilities.extras)
        val extras =
            catalog()
                .extras(library.movieWithResume.id, localTrailers = true, specialFeatures = true)
        assertTrue(extras.isEmpty())
    }

    @Test
    fun `a server without item collections gives none`() = runTest {
        assumeFalse(capabilities.itemCollections)
        assertTrue(catalog().collectionsOf(library.movieWithResume.id).isEmpty())
    }

    @Test
    fun `similar items are not the item itself`() = runTest {
        assumeTrue(capabilities.similarItems)
        val similar = catalog().similar(library.movieWithResume.id, limit = 12)

        assertFalse(library.movieWithResume.id in similar.map { it.id })
    }

    @Test
    fun `a series without a theme song has none`() = runTest {
        assumeTrue(capabilities.themeSongs)
        assertNull(catalog().themeSongUrl(library.series.id))
    }

    @Test
    fun `the episode of next up is the one item gives`() = runTest {
        val catalog = catalog()
        val nextUp = assertNotNull(catalog.nextUp(library.series.id, null).firstOrNull())
        val episode = catalog.episode(nextUp.id)

        assertEquals(nextUp.id, episode.id)
        assertEquals(nextUp.name, episode.name)
        assertEquals(library.season1.id, episode.seasonId)
    }
}
