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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
        // A Jellyfin source id comes dashed or not depending on the answer: both are kept as sent.
        val dashless = STORED.replace("-", "")
        val serverId = "Server-1".toServerId()
        val mediaSourceId = dashless.toMediaSourceId()

        assertEquals("Server-1", serverId.asString())
        assertEquals("Server-1", serverId.toString())
        assertEquals(dashless, mediaSourceId.asString())
        assertEquals(dashless, mediaSourceId.toString())
        assertEquals(STORED, STORED.toMediaSourceId().asString())
        assertEquals(ServerId("Server-1"), serverId)
        assertNotEquals(dashless.toMediaSourceId(), STORED.toMediaSourceId())
    }

    /** #918: a route or the Cast custom data holds an id where it held the id's String. */
    @Serializable
    private data class WithIds(
        val itemId: ItemId,
        val userId: UserId,
        val serverId: ServerId,
        val mediaSourceId: MediaSourceId?,
    )

    @Serializable
    private data class WithStrings(
        val itemId: String,
        val userId: String,
        val serverId: String,
        val mediaSourceId: String?,
    )

    @Test
    fun `kotlinx-serialization writes and reads an id as the plain string`() {
        val ids =
            WithIds(
                STORED.toItemId(),
                OTHER.toUserId(),
                "Server-1".toServerId(),
                "a".toMediaSourceId(),
            )
        val strings = WithStrings(STORED, OTHER, "Server-1", "a")
        val text = Json.encodeToString(WithStrings.serializer(), strings)

        assertEquals(text, Json.encodeToString(WithIds.serializer(), ids))
        assertEquals(ids, Json.decodeFromString(WithIds.serializer(), text))
        assertEquals("\"$STORED\"", Json.encodeToString(ItemId.serializer(), STORED.toItemId()))
        val noSource =
            Json.encodeToString(WithStrings.serializer(), strings.copy(mediaSourceId = null))
        assertEquals(
            ids.copy(mediaSourceId = null),
            Json.decodeFromString(WithIds.serializer(), noSource),
        )
    }

    private companion object {
        /** The dashed lowercase form Room holds for a Jellyfin id. */
        const val STORED = "0f8fad5b-d9cb-469f-a165-70867728950e"
        const val OTHER = "00000000-0000-0000-0000-000000000002"
    }
}
