package dev.jdtech.jellyfin.core.settings

import android.app.Application
import android.content.Context
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.repository.ResumeThresholds
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#637): each server's resume settings are kept for offline stops, and unknown ones are the
 * defaults.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SharedPreferencesResumeThresholdsStoreTest {
    private val preferences =
        RuntimeEnvironment.getApplication().getSharedPreferences("thresholds", Context.MODE_PRIVATE)
    private val store = SharedPreferencesResumeThresholdsStore(preferences)

    @Test
    fun `an unknown server has the defaults`() {
        assertEquals(ResumeThresholds.DEFAULT, store.get("never-read".toServerId()))
        assertEquals(ResumeThresholds.DEFAULT, store.get(null))
    }

    @Test
    fun `each server keeps its own`() {
        store.put("a".toServerId(), ResumeThresholds(2, 93, 2))
        store.put("b".toServerId(), ResumeThresholds(10, 80, 60))

        assertEquals(
            ResumeThresholds(2, 93, 2),
            SharedPreferencesResumeThresholdsStore(preferences).get("a".toServerId()),
        )
        assertEquals(
            ResumeThresholds(10, 80, 60),
            SharedPreferencesResumeThresholdsStore(preferences).get("b".toServerId()),
        )
    }

    @Test
    fun `a server read in this run is fresh`() {
        assertFalse(store.isFresh("c".toServerId()))
        store.put("c".toServerId(), ResumeThresholds.DEFAULT)
        assertTrue(store.isFresh("c".toServerId()))
    }

    @Test
    fun `a stored value that is not three numbers is ignored`() {
        assertNull(SharedPreferencesResumeThresholdsStore.parse("2,93"))
        assertNull(SharedPreferencesResumeThresholdsStore.parse("2,x,3"))
        assertEquals(
            ResumeThresholds(2, 93, 2),
            SharedPreferencesResumeThresholdsStore.parse("2,93,2"),
        )
    }
}
