package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.utils.VideoQualities
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #769: the download quality dialog offers the caps only where the server can transcode to one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadQualityDialogTest {
    @get:Rule val rule = createComposeRule()

    private val original =
        ApplicationProvider.getApplicationContext<Application>()
            .getString(CoreR.string.download_quality_original)
    private val caps = VideoQualities.bitrates.mapNotNull { VideoQualities.label(it) }

    private fun shown(capped: Boolean): List<String> {
        rule.setContent {
            FindroidTheme { DownloadQualityDialog(capped = capped, onSelect = {}, onDismiss = {}) }
        }
        rule.waitForIdle()
        return (listOf(original) + caps).filter { label ->
            rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `a server that caps downloads gets the original and every cap`() {
        // The list scrolls, so only check what a lazy list has composed: the first rungs.
        val shown = shown(capped = true)
        assertEquals(original, shown.first())
        assertTrue(shown.size > 1, shown.toString())
        assertEquals(caps.take(shown.size - 1), shown.drop(1))
    }

    @Test
    fun `a server that cannot gets the original only`() {
        assertEquals(listOf(original), shown(capped = false))
    }
}
