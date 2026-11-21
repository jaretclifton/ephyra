package dev.jdtech.jellyfin.repository

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.models.SeriesTrackPreferenceDto
import dev.jdtech.jellyfin.player.DeviceCodecProbe
import dev.jdtech.jellyfin.server.jellyfin.JellyfinDeviceProfiles
import dev.jdtech.jellyfin.server.jellyfin.JellyfinMediaServer
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#580, #750): the player saves and reads the series' track choice through the repository,
 * which keeps it per user.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SeriesTrackPreferenceTest {
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val otherUserId = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val seriesId = UUID.fromString("00000000-0000-0000-0000-000000000020")
    private val otherSeriesId = UUID.fromString("00000000-0000-0000-0000-000000000021")

    private val context: Context = RuntimeEnvironment.getApplication()
    private val room =
        Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val database = room.getServerDatabaseDao()
    private val preferences =
        AppPreferences(
            context.getSharedPreferences("series-track-preference", Context.MODE_PRIVATE)
        )

    private val api by lazy {
        // The SDK names the device after it; Robolectric has none.
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "test")
        JellyfinApi(context).apply { userId = this@SeriesTrackPreferenceTest.userId }
    }

    private val repository: JellyfinRepository by lazy {
        JellyfinRepositoryImpl(
            context,
            JellyfinMediaServer(api, JellyfinDeviceProfiles(DeviceCodecProbe(context))),
            database,
            preferences,
        )
    }

    @After fun close() = room.close()

    private val audio =
        SeriesTrackPreferenceDto(
            userId = userId,
            seriesId = seriesId,
            trackType = TRACK_TYPE_AUDIO,
            disabled = false,
            language = "jpn",
            title = "Japanese",
            positionInLanguage = 0,
        )

    @Test
    fun `a saved choice is read back for the same user and series`() = runBlocking {
        repository.saveSeriesTrackPreference(audio)

        assertEquals(listOf(audio), repository.getSeriesTrackPreferences(seriesId))
        assertTrue(repository.getSeriesTrackPreferences(otherSeriesId).isEmpty())
    }

    @Test
    fun `a saved choice is not read for another user`() = runBlocking {
        repository.saveSeriesTrackPreference(audio)

        api.userId = otherUserId
        assertTrue(repository.getSeriesTrackPreferences(seriesId).isEmpty())
    }

    @Test
    fun `a new choice replaces the one before for the same track type`() = runBlocking {
        repository.saveSeriesTrackPreference(audio)
        val subtitlesOff =
            SeriesTrackPreferenceDto(userId, seriesId, TRACK_TYPE_TEXT, disabled = true)
        repository.saveSeriesTrackPreference(subtitlesOff)
        val english = audio.copy(language = "eng", title = "English")
        repository.saveSeriesTrackPreference(english)

        assertEquals(
            setOf(english, subtitlesOff),
            repository.getSeriesTrackPreferences(seriesId).toSet(),
        )
    }

    @Test
    fun `the choice is saved for the current user whatever the preference says`() = runBlocking {
        repository.saveSeriesTrackPreference(audio.copy(userId = otherUserId))

        assertEquals(listOf(audio), repository.getSeriesTrackPreferences(seriesId))
        assertTrue(database.getSeriesTrackPreferences(otherUserId, seriesId).isEmpty())
    }

    private companion object {
        // media3's C.TRACK_TYPE_AUDIO and C.TRACK_TYPE_TEXT; data does not depend on media3.
        const val TRACK_TYPE_AUDIO = 1
        const val TRACK_TYPE_TEXT = 3
    }
}
