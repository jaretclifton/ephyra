package dev.jdtech.jellyfin.models

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.database.ServerDatabase
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #776: a downloaded video stream keeps its range type. The column stores the constant name, which
 * was read back by the server's serial name, so every value whose two names differ came back as
 * null: `UNKNOWN`, `HDR10_PLUS` and every Dolby Vision variant except plain `DOVI`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoredVideoRangeTypeTest {
    private val room =
        Room.inMemoryDatabaseBuilder(
                RuntimeEnvironment.getApplication(),
                ServerDatabase::class.java,
            )
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val database = room.getServerDatabaseDao()

    @After fun close() = room.close()

    private fun video(range: VideoRangeType?) =
        MediaStream(
            title = "",
            displayTitle = "4K",
            language = "",
            type = MediaStreamType.VIDEO,
            codec = "hevc",
            profile = "Main 10",
            isExternal = false,
            path = null,
            channelLayout = null,
            videoRangeType = range,
            height = 2160,
            width = 3840,
            videoDoViTitle = null,
        )

    @Test
    fun `every range type survives the entity mappers`() {
        VideoRangeType.entries.forEach { range ->
            val dto = video(range).toMediaStreamEntity(UUID.randomUUID(), "source", "path")

            assertEquals(range.name, dto.videoRangeType)
            assertEquals(range, dto.toMediaStream().videoRangeType)
        }
        val none = video(null).toMediaStreamEntity(UUID.randomUUID(), "source", "path")
        assertNull(none.videoRangeType)
        assertNull(none.toMediaStream().videoRangeType)
    }

    @Test
    fun `a download keeps the range type of its video stream`() = runBlocking {
        // Every value, the HDR10, HLG, HDR10+ and Dolby Vision ones included.
        VideoRangeType.entries.forEach { range ->
            val sourceId = "source-${range.name}"
            database.insertMediaStream(
                video(range).toMediaStreamEntity(UUID.randomUUID(), sourceId, "path")
            )

            val stored = database.getMediaStreamsBySourceId(sourceId).single()
            assertEquals(range, stored.toMediaStream().videoRangeType)
        }
    }

    @Test
    fun `a stored row with the serial name or an unknown value reads as expected`() {
        VideoRangeType.entries.forEach { assertEquals(it, storedVideoRangeType(it.serialName)) }
        assertNull(storedVideoRangeType(""))
        assertNull(storedVideoRangeType("DOVI_WITH_SOMETHING_NEW"))
    }
}
