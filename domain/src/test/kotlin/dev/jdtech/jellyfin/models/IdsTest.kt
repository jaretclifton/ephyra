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

    @Test
    fun `address and media stream ids keep their stored text`() {
        assertEquals(STORED, STORED.toServerAddressId().asString())
        assertEquals(STORED, STORED.toServerAddressId().toString())
        assertEquals(STORED, STORED.toMediaStreamId().asString())
        assertEquals("$STORED.srt", "${STORED.toMediaStreamId()}.srt")
        assertEquals(ServerAddressId(STORED), STORED.toServerAddressId())
        assertNotEquals(STORED.toServerAddressId(), OTHER.toServerAddressId())
    }

    // #918: the random ids replace UUID.randomUUID().toString(), which the UUID column converter
    // stored, so a new id has the same form as every stored one: 36 chars, lowercase, dashed
    // 8-4-4-4-12, version 4, IETF variant. java.util.UUID reads it back to the same text.
    @Test
    fun `random address and media stream ids have the text form UUID toString gave`() {
        val ids =
            List(200) { ServerAddressId.random().asString() } +
                List(200) { MediaStreamId.random().asString() }
        for (id in ids) {
            assertEquals(36, id.length, id)
            assertTrue(RANDOM_FORM.matches(id), id)
            assertTrue(id.looksLikeJellyfinId(), id)
            assertEquals(id, java.util.UUID.fromString(id).toString())
            assertEquals(4, java.util.UUID.fromString(id).version(), id)
        }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `boxed address and media stream ids survive Java serialization`() {
        val ids: List<Any> = listOf(STORED.toServerAddressId(), STORED.toMediaStreamId())
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(ArrayList(ids)) }
        val read =
            ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() }
        assertEquals(ids, read)
    }

    private companion object {
        val RANDOM_FORM =
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

        /** The dashed lowercase form Room holds for a Jellyfin id. */
        const val STORED = "0f8fad5b-d9cb-469f-a165-70867728950e"
        const val OTHER = "00000000-0000-0000-0000-000000000002"
    }
}
