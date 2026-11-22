package dev.jdtech.jellyfin.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
