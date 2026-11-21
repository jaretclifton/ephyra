package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.models.PersonKind
import kotlin.test.assertEquals
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
}
