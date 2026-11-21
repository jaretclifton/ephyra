package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.PersonKind
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Test

class SdkEnumsTest {
    @Test
    fun `every SDK person kind has a counterpart with the same name`() {
        org.jellyfin.sdk.model.api.PersonKind.entries.forEach { sdk ->
            assertEquals(sdk.name, sdk.toModel().name)
        }
    }

    @Test
    fun `both enums have the same number of constants`() {
        assertEquals(org.jellyfin.sdk.model.api.PersonKind.entries.size, PersonKind.entries.size)
    }

    @Test
    fun `kinds stay apart`() {
        assertEquals(PersonKind.EDITOR, org.jellyfin.sdk.model.api.PersonKind.EDITOR.toModel())
        assertEquals(PersonKind.ACTOR, org.jellyfin.sdk.model.api.PersonKind.ACTOR.toModel())
    }

    // The serial names are in intents and TV routes: they must stay the SDK's.
    @Test
    fun `every item kind has the serialName of its SDK kind`() {
        for (kind in ItemKind.entries - ItemKind.UNKNOWN) {
            assertEquals(kind.serialName, kind.toSdk().serialName)
        }
    }

    @Test
    fun `every item kind round-trips through the SDK kind`() {
        for (kind in ItemKind.entries - ItemKind.UNKNOWN) {
            assertEquals(kind, kind.toSdk().toItemKind())
        }
    }

    @Test
    fun `UNKNOWN has no SDK kind`() {
        assertFailsWith<IllegalArgumentException> { ItemKind.UNKNOWN.toSdk() }
    }

    @Test
    fun `an SDK item kind the app does not list is UNKNOWN`() {
        assertEquals(ItemKind.UNKNOWN, BaseItemKind.AUDIO.toItemKind())
        assertEquals(ItemKind.UNKNOWN, BaseItemKind.PERSON.toItemKind())
    }
}
