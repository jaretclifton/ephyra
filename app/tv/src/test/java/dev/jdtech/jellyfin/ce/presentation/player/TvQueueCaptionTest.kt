package dev.jdtech.jellyfin.ce.presentation.player

import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/** #865: the caption under a tile of the TV player's queue hides the name of a hidden episode. */
class TvQueueCaptionTest {
    private val episode =
        dummyEpisode.copy(
            name = "The Secret Ending",
            parentIndexNumber = 1,
            indexNumber = 4,
            indexNumberEnd = null,
        )

    @Test
    fun `a hidden episode shows only its number`() {
        assertEquals("S01E04", queueCaption(episode, hidden = true))
    }

    @Test
    fun `a hidden double episode shows both numbers`() {
        assertEquals("S01E04–E05", queueCaption(episode.copy(indexNumberEnd = 5), hidden = true))
    }

    @Test
    fun `a shown episode keeps its name`() {
        assertEquals("S01E04 – The Secret Ending", queueCaption(episode, hidden = false))
    }

    @Test
    fun `a movie keeps its name`() {
        assertEquals(dummyMovie.name, queueCaption(dummyMovie, hidden = false))
    }
}
