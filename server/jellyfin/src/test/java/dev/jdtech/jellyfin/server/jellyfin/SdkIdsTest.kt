package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toUserId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

/**
 * #773: the adapter turns the SDK's `UUID`s into the domain's ids in the dashed lowercase form Room
 * holds, and back. An id that is not a UUID is a programming error and throws.
 */
class SdkIdsTest {

    @Test
    fun `an SDK id becomes the dashed lowercase text`() {
        val uuid = UUID.fromString(STORED)
        assertEquals(STORED, uuid.toItemId().asString())
        assertEquals(STORED, uuid.toUserId().asString())
    }

    @Test
    fun `an id goes back to the same UUID`() {
        val uuid = UUID.fromString(STORED)
        assertEquals(uuid, STORED.toItemId().toUuid())
        assertEquals(uuid, STORED.toUserId().toUuid())
        assertEquals(uuid, uuid.toItemId().toUuid())
    }

    @Test
    fun `an id that is not a UUID throws instead of naming another item`() {
        assertFailsWith<IllegalArgumentException> { "12345".toItemId().toUuid() }
        assertFailsWith<IllegalArgumentException> { "".toUserId().toUuid() }
    }

    private companion object {
        const val STORED = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}
