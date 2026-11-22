package dev.jdtech.jellyfin.models

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The string form of the ids is what Room stores and routes carry (#773): it must come back
 * unchanged, before and after the ids become value classes.
 */
class IdsTest {

    @Test
    fun `an item id keeps its stored string form`() {
        assertEquals(STORED, STORED.toItemId().asString())
    }

    @Test
    fun `a user id keeps its stored string form`() {
        assertEquals(STORED, STORED.toUserId().asString())
    }

    @Test
    fun `ids of the same text are equal, of other text not`() {
        assertEquals(STORED.toItemId(), STORED.toItemId())
        assertNotEquals(STORED.toItemId(), OTHER.toItemId())
    }

    @Test
    fun `an id wraps any text, without parsing it`() {
        assertEquals("12345", "12345".toItemId().asString())
        assertEquals(STORED.uppercase(), STORED.uppercase().toUserId().asString())
    }

    @Test
    fun `an id prints as its text`() {
        assertEquals(STORED, STORED.toItemId().toString())
        assertEquals("id $STORED", "id ${STORED.toUserId()}")
    }

    // A boxed id goes into an Android Bundle (lazy list keys, rememberSaveable) as a Serializable.
    @Test
    fun `a boxed id survives Java serialization`() {
        val ids: List<Any> = listOf(STORED.toItemId(), STORED.toUserId())
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(ArrayList(ids)) }
        val read =
            ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() }
        assertEquals(ids, read)
    }

    @Test
    fun `only the dashed lowercase form looks like a Jellyfin id`() {
        assertTrue(STORED.looksLikeJellyfinId())
        assertFalse(STORED.uppercase().looksLikeJellyfinId())
        assertFalse(STORED.replace("-", "").looksLikeJellyfinId())
        assertFalse("1-2-3-4-5".looksLikeJellyfinId())
        assertFalse("home-cache".looksLikeJellyfinId())
        assertFalse("".looksLikeJellyfinId())
    }

    @Test
    fun `the server and source ids are the text the server sends`() {
        val serverId: ServerId = "server"
        val mediaSourceId: MediaSourceId = "source"

        assertEquals<String>("server", serverId)
        assertEquals<String>("source", mediaSourceId)
    }

    private companion object {
        /** The dashed lowercase form Room holds for a Jellyfin id. */
        const val STORED = "0f8fad5b-d9cb-469f-a165-70867728950e"
        const val OTHER = "00000000-0000-0000-0000-000000000002"
    }
}
