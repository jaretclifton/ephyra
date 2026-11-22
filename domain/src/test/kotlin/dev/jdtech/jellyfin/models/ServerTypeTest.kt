package dev.jdtech.jellyfin.models

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ServerTypeTest {
    @Test
    fun `each type is found by its value`() {
        ServerType.entries.forEach { assertEquals(it, ServerType.fromValue(it.value)) }
        assertEquals(ServerType.JELLYFIN, ServerType.fromValue("jellyfin"))
        assertEquals(ServerType.EMBY, ServerType.fromValue("emby"))
    }

    @Test
    fun `a value that is not exactly a type's is none`() {
        assertNull(ServerType.fromValue("Jellyfin"))
        assertNull(ServerType.fromValue("JELLYFIN"))
        assertNull(ServerType.fromValue(""))
        assertNull(ServerType.fromValue("plex"))
    }
}
