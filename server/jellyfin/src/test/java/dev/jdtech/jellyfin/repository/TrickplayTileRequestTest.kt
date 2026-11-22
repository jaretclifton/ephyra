package dev.jdtech.jellyfin.repository

import android.app.Application
import java.util.UUID
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CE (#860): the trickplay sheet request, as it reaches the server. Jellyfin looks the images up
 * under `mediaSourceId ?? itemId`, and the item's own version has the item's id as its source id
 * (in the 32-digit form the server writes it in). So the request of an item with one version stays
 * what it was, and only another version names itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TrickplayTileRequestTest {
    private val itemId = UUID.fromString("8f086228-6a92-020d-0cb7-35d734765afe")
    private val otherVersion = UUID.fromString("0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0")
    private val server = StandInServer { null }
    private val repository by lazy { server.repository() }

    @After
    fun stop() {
        server.close()
    }

    private fun sentTarget(): String = server.requests.single().target

    @Test
    fun `a sheet without a version asks for the item's images`() = runBlocking {
        repository.getTrickplayData(itemId, 320, 4)

        assertEquals("/Videos/$itemId/Trickplay/320/4.jpg", sentTarget())
    }

    @Test
    fun `a sheet of the item's own version is the same request`() = runBlocking {
        repository.getTrickplayData(
            itemId,
            320,
            4,
            mediaSourceId = itemId.toString().replace("-", ""),
        )

        assertEquals("/Videos/$itemId/Trickplay/320/4.jpg", sentTarget())
    }

    @Test
    fun `a sheet of another version names that version`() = runBlocking {
        repository.getTrickplayData(
            itemId,
            320,
            4,
            mediaSourceId = otherVersion.toString().replace("-", ""),
        )

        assertEquals(
            "/Videos/$itemId/Trickplay/320/4.jpg?mediaSourceId=$otherVersion",
            sentTarget(),
        )
    }

    @Test
    fun `a source id that is not an id asks for the item's images`() = runBlocking {
        repository.getTrickplayData(itemId, 320, 4, mediaSourceId = "not-an-id")

        assertEquals("/Videos/$itemId/Trickplay/320/4.jpg", sentTarget())
    }
}
