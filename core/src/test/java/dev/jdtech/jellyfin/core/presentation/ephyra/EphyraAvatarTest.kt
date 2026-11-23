package dev.jdtech.jellyfin.core.presentation.ephyra

import dev.jdtech.jellyfin.models.toUserId
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class EphyraAvatarTest {
    @Test
    fun `one word gives one letter`() {
        assertEquals("M", avatarInitials("midas"))
    }

    @Test
    fun `first and last word give two letters`() {
        assertEquals("MO", avatarInitials("Midas van Oene"))
    }

    @Test
    fun `a blank name gives a question mark`() {
        assertEquals("?", avatarInitials("  "))
        assertEquals("?", avatarInitials(""))
    }

    @Test
    fun `an emoji or combining mark is kept whole`() {
        assertEquals("😀", avatarInitials("😀"))
        assertEquals("😀S", avatarInitials("😀 smile"))
        assertEquals("É", avatarInitials("é"))
    }

    @Test
    fun `the colour index is stable and in range`() {
        val seed = "31e47044-9b79-4bb0-99d0-0e477ed65420".toUserId()
        val index = avatarColorIndex(seed, 4)
        assertEquals(index, avatarColorIndex(seed, 4))
        assertTrue(index in 0..3)
        repeat(200) {
            assertTrue(avatarColorIndex(UUID.randomUUID().toString().toUserId(), 4) in 0..3)
        }
    }

    // #773: user ids were UUIDs, and the colour came from UUID.hashCode(). A user keeps it.
    @Test
    fun `a user keeps the colour their UUID id gave`() {
        assertEquals(3, avatarColorIndex("31e47044-9b79-4bb0-99d0-0e477ed65420".toUserId(), 4))
        assertEquals(5, avatarColorIndex("31e47044-9b79-4bb0-99d0-0e477ed65420".toUserId(), 7))
        repeat(200) {
            val uuid = UUID.randomUUID()
            assertEquals(
                abs(uuid.hashCode().toLong()).toInt() % 7,
                avatarColorIndex(uuid.toString().toUserId(), 7),
            )
        }
    }

    @Test
    fun `an id that is not a UUID still gets a colour in range`() {
        assertTrue(avatarColorIndex("12345".toUserId(), 4) in 0..3)
        assertTrue(avatarColorIndex("".toUserId(), 4) in 0..3)
        // Its string hash is Int.MIN_VALUE, whose absolute value is no Int.
        assertEquals(Int.MIN_VALUE, "polygenelubricants".hashCode())
        assertTrue(avatarColorIndex("polygenelubricants".toUserId(), 7) in 0..6)
    }
}
