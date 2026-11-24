package dev.jdtech.jellyfin.ratings.mdblist

import dev.jdtech.jellyfin.domain.ratings.ExternalIdType
import dev.jdtech.jellyfin.domain.ratings.ExternalRating
import dev.jdtech.jellyfin.domain.ratings.RatedKind
import dev.jdtech.jellyfin.domain.ratings.RatingSource
import dev.jdtech.jellyfin.domain.ratings.RatingsLookup
import dev.jdtech.jellyfin.ratings.RatingsAnswer
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** CE (#331): [MdbListClient] against [FakeMdbList], one test per kind of answer. */
class MdbListClientTest {
    private val server = FakeMdbList()
    private val lookup = RatingsLookup(ExternalIdType.TMDB, RatedKind.MOVIE, "278")

    @AfterEach fun stop() = server.close()

    private fun title(readTimeoutMs: Long = 2_000) = runBlocking {
        server.client(readTimeoutMs).title(lookup, "secret-key")
    }

    @Test
    fun `asks for the documented route with the key as apikey`() {
        title()
        assertEquals(listOf("/tmdb/movie/278/?apikey=secret-key"), server.requests.toList())
    }

    @Test
    fun `names the id type and the kind in the route`() {
        val client = server.client()
        runBlocking {
            client.title(RatingsLookup(ExternalIdType.TVDB, RatedKind.SHOW, "81189"), "k")
            client.title(RatingsLookup(ExternalIdType.IMDB, RatedKind.MOVIE, "tt0111161"), "k")
        }
        assertEquals(
            listOf("/tvdb/show/81189/?apikey=k", "/imdb/movie/tt0111161/?apikey=k"),
            server.requests.toList(),
        )
    }

    @Test
    fun `keeps the shown sites in a fixed order`() {
        val result = title()
        assertEquals(
            RatingsAnswer.Found(
                listOf(
                    ExternalRating(RatingSource.IMDB, 9.3f),
                    ExternalRating(RatingSource.ROTTEN_TOMATOES, 89f),
                    ExternalRating(RatingSource.METACRITIC, 82f),
                    ExternalRating(RatingSource.LETTERBOXD, 4.5f),
                )
            ),
            result,
        )
    }

    @Test
    fun `drops sites without a score`() {
        server.respond = { FakeMdbList.Reply(200, FakeMdbList.SPARSE_BODY) }
        assertEquals(RatingsAnswer.Found(listOf(ExternalRating(RatingSource.IMDB, 6.1f))), title())
    }

    @Test
    fun `a title without ratings is found with none`() {
        server.respond = { FakeMdbList.Reply(200, """{"id": 1, "title": "x"}""") }
        assertEquals(RatingsAnswer.Found(emptyList()), title())
    }

    @Test
    fun `401 and 403 are an invalid key`() {
        server.respond = { FakeMdbList.Reply(401, FakeMdbList.ERROR_BODY) }
        assertEquals(RatingsAnswer.InvalidKey, title())
        server.respond = { FakeMdbList.Reply(403, FakeMdbList.ERROR_BODY) }
        assertEquals(RatingsAnswer.InvalidKey, title())
    }

    @Test
    fun `404 is not found`() {
        server.respond = { FakeMdbList.Reply(404, """{"error": "Not found"}""") }
        assertEquals(RatingsAnswer.NotFound, title())
    }

    @Test
    fun `429 passes Retry-After on`() {
        server.respond = {
            FakeMdbList.Reply(
                429,
                """{"error": "API rate limit exceeded!"}""",
                headers = mapOf("Retry-After" to "120"),
            )
        }
        assertEquals(RatingsAnswer.RateLimited(120), title())
    }

    @Test
    fun `429 without Retry-After`() {
        server.respond = { FakeMdbList.Reply(429, """{"error": "Daily API limit exceeded!"}""") }
        assertEquals(RatingsAnswer.RateLimited(null), title())
    }

    @Test
    fun `a timeout fails`() {
        server.respond = { FakeMdbList.Reply(200, FakeMdbList.TITLE_BODY, delayMs = 1_500) }
        assertEquals(RatingsAnswer.Failed, title(readTimeoutMs = 300))
    }

    @Test
    fun `a malformed body fails`() {
        for (body in listOf("<html>oops</html>", "[]", """{"ratings": {"imdb": 7}}""", "")) {
            server.respond = { FakeMdbList.Reply(200, body) }
            assertEquals(RatingsAnswer.Failed, title(), message = body)
        }
    }

    @Test
    fun `a server error fails`() {
        server.respond = { FakeMdbList.Reply(502, "Bad gateway") }
        assertEquals(RatingsAnswer.Failed, title())
    }

    @Test
    fun `nothing reachable fails`() {
        val url = server.baseUrl
        server.close()
        val client = MdbListClient(url.toHttpUrl())
        assertEquals(RatingsAnswer.Failed, runBlocking { client.title(lookup, "k") })
    }

    @Test
    fun `a redirect is not followed, so the key stays with MDBList`() {
        FakeMdbList().use { elsewhere ->
            server.respond = {
                FakeMdbList.Reply(302, headers = mapOf("Location" to elsewhere.baseUrl + "steal"))
            }
            assertEquals(RatingsAnswer.Failed, title())
            assertTrue(elsewhere.requests.isEmpty(), message = elsewhere.requests.toString())
        }
    }

    @Test
    fun `the key check asks for user`() {
        server.respond = { FakeMdbList.Reply(200, """{"user_id": 1, "username": "u"}""") }
        val result = runBlocking { server.client().checkKey("secret-key") }
        assertEquals(RatingsAnswer.Found(emptyList()), result)
        assertEquals(listOf("/user?apikey=secret-key"), server.requests.toList())
    }

    @Test
    fun `the key check turns a 401 into an invalid key`() {
        server.respond = { FakeMdbList.Reply(401, FakeMdbList.ERROR_BODY) }
        assertEquals(RatingsAnswer.InvalidKey, runBlocking { server.client().checkKey("wrong") })
    }
}
