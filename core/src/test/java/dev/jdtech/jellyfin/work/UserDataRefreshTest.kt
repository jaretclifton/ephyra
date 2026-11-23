package dev.jdtech.jellyfin.work

import dev.jdtech.jellyfin.models.toItemId
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * CE (#635): how often the app asks the server for the user data of downloads while it is in
 * use. #898: what the sync copies into the rows is a rule of `PlayState`, tested in `domain`
 * (`SyncMergeTest`).
 */
class UserDataRefreshTest {

    @Test
    fun `the first refresh is always due`() {
        assertTrue(refreshDue(lastAt = null, now = 0L, intervalMillis = INTERVAL))
    }

    @Test
    fun `a refresh is due again only after the interval`() {
        assertFalse(
            refreshDue(lastAt = 1_000L, now = 1_000L + INTERVAL - 1, intervalMillis = INTERVAL)
        )
        assertTrue(refreshDue(lastAt = 1_000L, now = 1_000L + INTERVAL, intervalMillis = INTERVAL))
    }

    @Test
    fun `a run refreshes the downloads only while nothing is playing here`() {
        assertTrue(refreshesDownloads(hasDownloads = true, playingHere = null))
        assertFalse(
            refreshesDownloads(
                hasDownloads = true,
                playingHere = UUID.randomUUID().toString().toItemId(),
            )
        )
        assertFalse(refreshesDownloads(hasDownloads = false, playingHere = null))
    }

    private companion object {
        const val INTERVAL = 15 * 60 * 1000L
    }
}
