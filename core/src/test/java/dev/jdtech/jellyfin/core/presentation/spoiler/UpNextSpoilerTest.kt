package dev.jdtech.jellyfin.core.presentation.spoiler

import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test

/** #856: the player's up-next card hides the next episode like the episode list does. */
class UpNextSpoilerTest {
    private val on = Spoilers(enabled = true)

    private fun episode(played: Boolean, number: Int = 4) =
        dummyEpisode.copy(id = UUID.randomUUID(), played = played, indexNumber = number)

    @Test
    fun `an unwatched next episode is hidden and numbered`() {
        val next = episode(played = false, number = 4)
        assertEquals(
            UpNextSpoiler(hidden = true, episodeNumber = 4),
            on.upNext(next.id, listOf(next)),
        )
    }

    @Test
    fun `a watched next episode is shown`() {
        val next = episode(played = true)
        assertFalse(on.upNext(next.id, listOf(next)).hidden)
    }

    @Test
    fun `nothing is hidden with the setting off`() {
        val next = episode(played = false)
        assertFalse(Spoilers.Off.upNext(next.id, listOf(next)).hidden)
    }

    @Test
    fun `an episode that is not in the queue yet stays hidden, without a number`() {
        assertEquals(
            UpNextSpoiler(hidden = true, episodeNumber = null),
            on.upNext(UUID.randomUUID(), emptyList()),
        )
    }

    @Test
    fun `a revealed episode is shown`() {
        val next = episode(played = false)
        on.reveal(next)
        assertFalse(on.upNext(next.id, listOf(next)).hidden)
    }

    @Test
    fun `only the next episode counts, not another queued one`() {
        val next = episode(played = true, number = 2)
        val later = episode(played = false, number = 3)
        assertFalse(on.upNext(next.id, listOf(next, later)).hidden)
    }
}
