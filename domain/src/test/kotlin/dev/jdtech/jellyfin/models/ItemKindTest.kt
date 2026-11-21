package dev.jdtech.jellyfin.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ItemKindTest {

    @Test
    fun `every serialName round-trips through fromName`() {
        for (kind in ItemKind.entries) {
            assertEquals(kind, ItemKind.fromName(kind.serialName))
        }
    }

    @Test
    fun `fromName matches the serialName`() {
        assertEquals(ItemKind.EPISODE, ItemKind.fromName("Episode"))
    }

    @Test
    fun `fromName throws for an enum name or an unknown name`() {
        assertFailsWith<IllegalArgumentException> { ItemKind.fromName("EPISODE") }
        assertFailsWith<IllegalArgumentException> { ItemKind.fromName("x") }
    }
}
