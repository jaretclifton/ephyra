package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.models.MediaStreamType
import dev.jdtech.jellyfin.models.PersonKind
import dev.jdtech.jellyfin.models.SubtitleDeliveryMethod
import dev.jdtech.jellyfin.models.VideoRangeType
import kotlin.test.assertEquals
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

    @Test
    fun `video range types are read by serial name like the SDK does`() {
        // Today's behaviour, bug #776: a download stores the constant name and reads it back by
        // serial name, so only the types whose two names are equal survive.
        SdkVideoRangeType.entries.forEach {
            assertEquals(
                SdkVideoRangeType.fromNameOrNull(it.name)?.name,
                VideoRangeType.fromNameOrNull(it.name)?.name,
            )
            assertEquals(it.name, VideoRangeType.fromNameOrNull(it.serialName)?.name)
        }
        assertEquals(null, VideoRangeType.fromNameOrNull(""))
    }
}
