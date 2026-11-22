package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.UserDataPort
import dev.jdtech.jellyfin.models.ServerException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The contract of [UserDataPort] (#771): the marks it sets are what the catalog gives back. Each
 * test puts back what it changed, so a live server ends as it started.
 */
abstract class UserDataContract {
    /** A server that presents [ContractLibrary]. */
    abstract fun server(): MediaServer

    private val library = ContractLibrary

    @Test
    fun `a favourite round-trips through item`() = runTest {
        val server = server()
        val id = library.movieWithResume.id

        server.userData.setFavorite(id, true)
        assertEquals(true, server.catalog.item(id)?.favorite)
        server.userData.setFavorite(id, false)
        assertEquals(false, server.catalog.item(id)?.favorite)
    }

    @Test
    fun `played round-trips through item`() = runTest {
        val server = server()
        val id = library.favoriteMovie.id

        server.userData.setPlayed(id, true)
        assertEquals(true, server.catalog.item(id)?.played)
        server.userData.setPlayed(id, false)
        assertEquals(false, server.catalog.item(id)?.played)
    }

    @Test
    fun `an episode marked played moves next up on`() = runTest {
        val server = server()
        val next = library.nextUp.id

        server.userData.setPlayed(next, true)
        val after = server.catalog.nextUp(library.series.id, dateCutoff = null).firstOrNull()?.id
        server.userData.setPlayed(next, false)

        assertEquals(library.season1Episodes[2].id, after)
    }

    @Test
    fun `an unknown item fails with an error status`() = runTest {
        val server = server()

        val favorite =
            assertFailsWith<ServerException> {
                server.userData.setFavorite(library.unknownId, true)
            }
        assertIs<ServerException.HttpStatus>(favorite)
        val played =
            assertFailsWith<ServerException> { server.userData.setPlayed(library.unknownId, true) }
        assertIs<ServerException.HttpStatus>(played)
    }
}
