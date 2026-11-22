package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.models.EpisodeField
import dev.jdtech.jellyfin.models.ItemFilter
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.MediaStreamType
import dev.jdtech.jellyfin.models.PersonKind
import dev.jdtech.jellyfin.models.SubtitleDeliveryMethod
import dev.jdtech.jellyfin.models.VideoRangeType
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType as SdkMediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod as SdkSubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.VideoRangeType as SdkVideoRangeType
import org.junit.jupiter.api.Test

/**
 * Every SDK constant has a counterpart of the same name, so an SDK update that adds one fails here
 * and not in a user's database read. Room stores [MediaStreamType] by constant name (#741).
 */
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

    @Test
    fun `every media stream type converts by name`() {
        assertEquals(
            SdkMediaStreamType.entries.map { it.name },
            MediaStreamType.entries.map { it.name },
        )
        SdkMediaStreamType.entries.forEach { assertEquals(it.name, it.toModel().name) }
    }

    @Test
    fun `every subtitle delivery method converts by name`() {
        assertEquals(
            SdkSubtitleDeliveryMethod.entries.map { it.name },
            SubtitleDeliveryMethod.entries.map { it.name },
        )
        SdkSubtitleDeliveryMethod.entries.forEach { assertEquals(it.name, it.toModel().name) }
    }

    @Test
    fun `every video range type converts by name and keeps the serial name`() {
        assertEquals(
            SdkVideoRangeType.entries.map { it.name },
            VideoRangeType.entries.map { it.name },
        )
        SdkVideoRangeType.entries.forEach {
            val model = it.toModel()
            assertEquals(it.name, model.name)
            assertEquals(it.serialName, model.serialName)
        }
    }

    // The item kinds' serial names are in intents and TV routes: they must stay the SDK's.
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

    @Test
    fun `every episode field and item filter converts to the SDK constant of the same name`() {
        EpisodeField.entries.forEach { assertEquals(it.name, it.toSdk().name) }
        ItemFilter.entries.forEach { assertEquals(it.name, it.toSdk().name) }
    }

    @Test
    fun `no filters are sent as no parameter`() {
        assertEquals(null, emptySet<ItemFilter>().toSdkFilters())
        assertEquals(
            listOf(org.jellyfin.sdk.model.api.ItemFilter.IS_UNPLAYED),
            setOf(ItemFilter.IS_UNPLAYED).toSdkFilters(),
        )
    }
}
