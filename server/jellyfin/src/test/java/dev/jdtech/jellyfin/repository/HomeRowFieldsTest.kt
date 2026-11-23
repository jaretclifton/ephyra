package dev.jdtech.jellyfin.repository

import android.app.Application
import dev.jdtech.jellyfin.models.toItemId
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CE (#410): the Home rows other than Suggestions ask for the overview. Jellyfin leaves `Overview`
 * out of Resume, Next Up and Latest unless `fields` names it, and the TV hero shows it for every
 * card. The server here only records each request's query and answers with an empty list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeRowFieldsTest {
    private val server = StandInServer { request ->
        // Latest answers with a bare list, the others with a result page.
        if (request.path.startsWith("/Items/Latest")) "[]"
        else """{"Items":[],"TotalRecordCount":0,"StartIndex":0}"""
    }

    @After
    fun stop() {
        server.close()
    }

    private fun repository(): MediaRepositoryImpl = server.repository()

    private fun assertAsksForOverview(endpoint: String) {
        val request = server.requests.single { it.target.contains(endpoint) }.decodedTarget
        assertTrue(
            Regex("""[?&]fields=[^&]*\bOverview\b""").containsMatchIn(request),
            message = request,
        )
    }

    @Test
    fun `Continue Watching asks for the overview`() = runBlocking {
        repository().getResumeItems()
        assertAsksForOverview("/UserItems/Resume")
    }

    @Test
    fun `Next Up asks for the overview`() = runBlocking {
        repository().getNextUp()
        assertAsksForOverview("/Shows/NextUp")
    }

    @Test
    fun `Latest asks for the overview`() = runBlocking {
        repository().getLatestMedia("22222222-2222-2222-2222-222222222222".toItemId())
        assertAsksForOverview("/Items/Latest")
    }
}
