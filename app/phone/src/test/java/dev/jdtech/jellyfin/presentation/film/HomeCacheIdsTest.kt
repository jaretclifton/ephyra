package dev.jdtech.jellyfin.presentation.film

import android.app.Application
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.film.presentation.home.HomeCache
import dev.jdtech.jellyfin.film.presentation.home.HomeState
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toUserId
import java.io.File
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #773: the Home cache keeps ids as text in its JSON file. With ids as value classes, a file
 * written before reads back as the same ids, and a new file holds the same text in the same place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeCacheIdsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val cache = HomeCache(context)
    private val file = File(context.filesDir, "home-cache/server_$USER.json")

    @Test
    fun `a cache file written with UUID ids reads back as the same ids`() = runBlocking {
        file.parentFile!!.mkdirs()
        file.writeText(STORED_BEFORE)

        val rows = cache.read("server", USER.toUserId())!!
        val suggestions = rows.suggestions!!
        assertEquals(SECTION.toItemId(), suggestions.id)
        val episode = suggestions.items.single() as Episode
        assertEquals(EPISODE.toItemId(), episode.id)
        assertEquals(SHOW.toItemId(), episode.seriesId)
        assertEquals(SEASON.toItemId(), episode.seasonId)
    }

    @Test
    fun `a written cache file holds the ids as the same text`() = runBlocking {
        val episode =
            dummyEpisode.copy(
                id = EPISODE.toItemId(),
                seriesId = SHOW.toItemId(),
                seasonId = SEASON.toItemId(),
            )
        cache.write(
            "server",
            USER.toUserId(),
            HomeState(
                suggestionsSection = HomeItem.Suggestions(SECTION.toItemId(), listOf(episode))
            ),
        )

        val suggestions = JSONObject(file.readText()).getJSONObject("suggestions")
        assertEquals(SECTION, suggestions.getString("id"))
        val item = suggestions.getJSONArray("items").getJSONObject(0)
        assertEquals(EPISODE, item.getString("id"))
        assertEquals(SHOW, item.getString("seriesId"))
        assertEquals(SEASON, item.getString("seasonId"))
    }

    private companion object {
        const val USER = "0f8fad5b-d9cb-469f-a165-70867728950e"
        const val SECTION = "00000000-0000-0000-0000-000000000001"
        const val EPISODE = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
        const val SHOW = "16fd2706-8baf-433b-82eb-8c7fada847da"
        const val SEASON = "886313e1-3b8a-5372-9b90-0c9aee199e5d"

        /** A cache file as the app wrote it while ids were `UUID`s (version 2). */
        val STORED_BEFORE =
            """
            {"version":2,"rowOrder":[],"views":[],
             "suggestions":{"id":"$SECTION","items":[
              {"id":"$EPISODE","name":"Obstacle Course","overview":"","played":false,
               "favorite":false,"canPlay":true,"canDownload":true,"runtime":0,"position":0,
               "unplayed":null,"images":{"primary":null,"backdrop":null,"logo":null,
               "showPrimary":null,"showBackdrop":null,"showLogo":null,"thumb":null,
               "showThumb":null},
               "kind":"episode","index":1,"indexEnd":null,"parentIndex":1,
               "seriesId":"$SHOW","seriesName":"Bluey","seasonId":"$SEASON",
               "seasonName":"Season 1"}]}}
            """
                .trimIndent()
    }
}
