package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.models.MediaStreamType
import dev.jdtech.jellyfin.models.SubtitleDeliveryMethod
import dev.jdtech.jellyfin.models.VideoRangeType
import kotlin.test.assertEquals
import org.jellyfin.sdk.model.api.MediaStreamType as SdkMediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod as SdkSubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.VideoRangeType as SdkVideoRangeType
import org.junit.jupiter.api.Test

/**
 * Every SDK constant has a counterpart of the same name (#741), so an SDK update that adds one
 * fails here and not in a user's database read. Room stores [MediaStreamType] by constant name.
 */
class SdkEnumsTest {
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
