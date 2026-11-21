package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.models.ItemKind
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Test

class SdkEnumsTest {

    // The serial names are in intents and TV routes: they must stay the SDK's.
    @Test
    fun `every kind has the serialName of its SDK kind`() {
        for (kind in ItemKind.entries - ItemKind.UNKNOWN) {
            assertEquals(kind.serialName, kind.toSdk().serialName)
        }
    }

    @Test
    fun `every kind round-trips through the SDK kind`() {
        for (kind in ItemKind.entries - ItemKind.UNKNOWN) {
            assertEquals(kind, kind.toSdk().toItemKind())
        }
    }

    @Test
    fun `UNKNOWN has no SDK kind`() {
        assertFailsWith<IllegalArgumentException> { ItemKind.UNKNOWN.toSdk() }
    }

    @Test
    fun `an SDK kind the app does not list is UNKNOWN`() {
        assertEquals(ItemKind.UNKNOWN, BaseItemKind.AUDIO.toItemKind())
        assertEquals(ItemKind.UNKNOWN, BaseItemKind.PERSON.toItemKind())
    }
}
