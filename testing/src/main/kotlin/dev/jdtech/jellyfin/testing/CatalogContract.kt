package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.domain.ports.CatalogPort
import dev.jdtech.jellyfin.domain.ports.ItemQuery
import dev.jdtech.jellyfin.models.BoxSet
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.asString
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
 * the status of a malformed id), so a server of another type can pass them. What is gated on a
 * capability reads it from [server], the implementation under test.
 */
abstract class CatalogContract : MediaServerContract() {
    private fun catalog(): CatalogPort = server().catalog

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

        assertIs<Movie>(catalog.item(library.favoriteMovie.id))
        assertIs<Show>(catalog.item(library.series.id))
        val episode = assertIs<Episode>(catalog.item(library.nextUp.id))
        assertEquals(library.season1.id, episode.seasonId)
        assertEquals(library.series.id, episode.seriesId)
    }

    @Test
    fun `item gives null for a kind the app does not handle`() = runTest {
        assumeOnServer(Part.PERSON)
        // A person is an item on the server, of a kind the app shows only as a person.
        assertNull(catalog().item(library.person.id))
    }

    @Test
    fun `a box set is a box set`() = runTest {
        assumeOnServer(Part.BOX_SET)
        val boxSet = assertIs<BoxSet>(catalog().item(library.boxSet.id))

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
        assertTrue(movies.items.all { it is Movie }, "movies: ${movies.items}")
        assertTrue(shows.items.isNotEmpty())
        assertTrue(shows.items.all { it is Show }, "shows: ${shows.items}")
    }

    private val season1Query =
        ItemQuery(parentId = library.season1.id, kinds = listOf(ItemKind.EPISODE))

    @Test
    fun `a page reports the total only when asked`() = runTest {
        val catalog = catalog()

        assertNull(catalog.items(season1Query).totalRecordCount)
        val counted = catalog.items(season1Query.copy(limit = 2), includeTotal = true)
        assertEquals(library.season1Episodes.size, counted.totalRecordCount)
        assertEquals(2, counted.items.size)
        assertEquals(library.season1Episodes.size, catalog.itemCount(season1Query.copy(limit = 2)))
    }

    @Test
    fun `start index and limit page through the items`() = runTest {
        val catalog = catalog()
        val all = catalog.items(season1Query).items.map { it.id }
        val page = catalog.items(season1Query.copy(startIndex = 1, limit = 2)).items.map { it.id }

        assertEquals(library.season1Episodes.map { it.id }.toSet(), all.toSet())
        assertEquals(all.drop(1).take(2), page)
    }

    @Test
    fun `a page past the end is empty and still counts all`() = runTest {
        val past = library.season1Episodes.size + 5
        val page = catalog().items(season1Query.copy(startIndex = past), includeTotal = true)

        assertTrue(page.items.isEmpty(), "items: ${page.items}")
        assertEquals(library.season1Episodes.size, page.totalRecordCount)
    }

    @Test
    fun `items come in the sort order and direction asked for`() = runTest {
        val catalog = catalog()
        val query = season1Query.copy(sortBy = SortBy.NAME)
        val ascending = catalog.items(query.copy(sortOrder = SortOrder.ASCENDING)).items
        val descending = catalog.items(query.copy(sortOrder = SortOrder.DESCENDING)).items

        // The episodes' names are distinct and in episode order.
        assertEquals(library.season1Episodes.map { it.id }, ascending.map { it.id })
        assertEquals(ascending.map { it.id }.reversed(), descending.map { it.id })
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
        val found = catalog().search("Versions", kinds = null).map { it.id }

        assertTrue(library.movieWithTwoVersions.id in found, "found: $found")
        // A search that ignored the word would give every movie.
        assertFalse(library.movieWithResume.id in found, "found: $found")
        assertFalse(library.series.id in found, "found: $found")
    }

    @Test
    fun `search of a kind finds only that kind`() = runTest {
        val found = catalog().search("Test Show", kinds = listOf(ItemKind.SERIES))

        assertTrue(library.series.id in found.map { it.id }, "found: $found")
        assertTrue(found.all { it is Show }, "found: $found")
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
        val catalog = failingServer().catalog
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

        for (call in calls) assertErrorStatus(call)
    }

    @Test
    fun `a server that does not answer gives an unanswered failure`() = runTest {
        val catalog = unreachableServer().catalog

        assertUnanswered { catalog.item(library.movieWithResume.id) }
        assertUnanswered { catalog.items(ItemQuery(parentId = library.season1.id)) }
    }

    @Test
    fun `a call cancelled while it waits is cancelled`() = runTest {
        val catalog = stallingServer().catalog

        assertCancellable { catalog.item(library.movieWithResume.id) }
    }

    @Test
    fun `a server without theme songs gives none`() = runTest {
        val server = server()
        assumeFalse(server.capabilities.themeSongs)
        assertNull(server.catalog.themeSongUrl(library.series.id))
    }

    @Test
    fun `a server without similar items gives none`() = runTest {
        val server = server()
        assumeFalse(server.capabilities.similarItems)
        assertTrue(server.catalog.similar(library.movieWithResume.id, limit = 12).isEmpty())
    }

    @Test
    fun `a server without extras gives none`() = runTest {
        val server = server()
        assumeFalse(server.capabilities.extras)
        val extras =
            server.catalog.extras(
                library.movieWithResume.id,
                localTrailers = true,
                specialFeatures = true,
            )
        assertTrue(extras.isEmpty())
    }

    @Test
    fun `a server without item collections gives none`() = runTest {
        val server = server()
        assumeFalse(server.capabilities.itemCollections)
        assertTrue(server.catalog.collectionsOf(library.movieWithResume.id).isEmpty())
    }

    @Test
    fun `similar items are there and are not the item itself`() = runTest {
        val server = server()
        assumeTrue(server.capabilities.similarItems)
        assumeOnServer(Part.SIMILAR_ITEMS)
        val similar = server.catalog.similar(library.movieWithResume.id, limit = 12).map { it.id }

        assertTrue(similar.isNotEmpty(), "no similar items")
        assertFalse(library.movieWithResume.id in similar, "similar: $similar")
    }

    @Test
    fun `the series has its theme song`() = runTest {
        val server = server()
        assumeTrue(server.capabilities.themeSongs)
        assumeOnServer(Part.THEME_SONG)
        val url = assertNotNull(server.catalog.themeSongUrl(library.series.id))

        assertTrue(url.startsWith("http"), url)
        assertTrue(library.seriesThemeSongId.asString() in url, url)
    }

    @Test
    fun `a movie without a theme song has none`() = runTest {
        val server = server()
        assumeTrue(server.capabilities.themeSongs)
        assertNull(server.catalog.themeSongUrl(library.movieWithResume.id))
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
