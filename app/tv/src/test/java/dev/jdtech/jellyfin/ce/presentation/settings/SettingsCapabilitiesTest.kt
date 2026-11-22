package dev.jdtech.jellyfin.ce.presentation.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.Constants
import dev.jdtech.jellyfin.settings.presentation.enums.AppVariant
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceSelect
import dev.jdtech.jellyfin.settings.presentation.settings.SettingsViewModel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #769: the settings of a feature the server lacks are not shown, on both variants, and the
 * auto-play select has no "Use server setting" for a server without that setting. With
 * [ServerCapabilities.ALL] (Jellyfin) everything is as before. The capabilities are read on every
 * load, so a switch of server shows on the next one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsCapabilitiesTest {
    private lateinit var context: Context
    private lateinit var appPreferences: AppPreferences
    private var capabilities = ServerCapabilities.ALL

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        appPreferences =
            AppPreferences(context.getSharedPreferences("capabilities", Context.MODE_PRIVATE))
    }

    private fun viewModel(variant: AppVariant) =
        SettingsViewModel(context, appPreferences, variant, serverCapabilities = { capabilities })

    private fun SettingsViewModel.playerGroups(): List<Int?> {
        loadPreferences(listOf(SettingsR.string.settings_category_player))
        return state.value.preferenceGroups.map { it.nameStringResource }
    }

    private fun SettingsViewModel.autoPlayNext(): PreferenceSelect {
        loadPreferences(listOf(SettingsR.string.settings_category_player))
        return state.value.preferenceGroups
            .flatMap { it.preferences }
            .filterIsInstance<PreferenceSelect>()
            .first { it.nameStringResource == SettingsR.string.pref_player_autoplay_next }
    }

    private fun SettingsViewModel.interfaceSettings(): List<Int> {
        loadPreferences(listOf(SettingsR.string.settings_category_interface))
        return state.value.preferenceGroups.flatMap { it.preferences }.map { it.nameStringResource }
    }

    @Test
    fun `Jellyfin shows the segment and trickplay settings on both variants`() = runTest {
        for (variant in AppVariant.entries) {
            val groups = viewModel(variant).playerGroups()

            assertTrue(groups.contains(SettingsR.string.media_segments), "$variant")
            assertTrue(groups.contains(SettingsR.string.trickplay), "$variant")
        }
    }

    @Test
    fun `without media segments or trickplay their settings are gone on both variants`() = runTest {
        for (variant in AppVariant.entries) {
            capabilities = ServerCapabilities.ALL.copy(mediaSegments = false)
            val viewModel = viewModel(variant)
            var groups = viewModel.playerGroups()
            assertFalse(groups.contains(SettingsR.string.media_segments), "$variant")
            assertTrue(groups.contains(SettingsR.string.trickplay), "$variant")

            // The next load follows the server in use now.
            capabilities = ServerCapabilities.ALL.copy(trickplay = false)
            groups = viewModel.playerGroups()
            assertTrue(groups.contains(SettingsR.string.media_segments), "$variant")
            assertFalse(groups.contains(SettingsR.string.trickplay), "$variant")
        }
    }

    @Test
    fun `Jellyfin's auto-play select keeps its server choice and summary`() = runTest {
        for (variant in AppVariant.entries) {
            val select = viewModel(variant).autoPlayNext()

            assertEquals(SettingsR.array.player_autoplay_next, select.options)
            assertEquals(SettingsR.array.player_autoplay_next_values, select.optionValues)
            assertEquals(
                SettingsR.string.pref_player_autoplay_next_summary,
                select.descriptionStringRes,
            )
            assertEquals(Constants.PlayerAutoPlayNext.SERVER, select.value)
        }
    }

    @Test
    fun `without the server setting auto-play offers Always and Never`() = runTest {
        capabilities = ServerCapabilities.ALL.copy(autoPlayNextSetting = false)
        for (variant in AppVariant.entries) {
            val select = viewModel(variant).autoPlayNext()

            assertEquals(SettingsR.array.ce_player_autoplay_next_local, select.options)
            assertEquals(
                listOf(Constants.PlayerAutoPlayNext.ON, Constants.PlayerAutoPlayNext.OFF),
                context.resources.getStringArray(select.optionValues).toList(),
            )
            assertNull(select.descriptionStringRes)
            // The stored default follows the server; the player then plays on, as Always.
            assertEquals(Constants.PlayerAutoPlayNext.ON, select.value)
        }
        // Showing it changed nothing that is stored.
        assertEquals(
            Constants.PlayerAutoPlayNext.SERVER,
            appPreferences.getValue(appPreferences.playerAutoPlayNext),
        )
    }

    @Test
    fun `without theme songs the TV has no theme music switch`() = runTest {
        assertTrue(
            viewModel(AppVariant.TV)
                .interfaceSettings()
                .contains(SettingsR.string.pref_tv_theme_music)
        )

        capabilities = ServerCapabilities.ALL.copy(themeSongs = false)
        val names = viewModel(AppVariant.TV).interfaceSettings()

        assertFalse(names.contains(SettingsR.string.pref_tv_theme_music))
        // The rest of its group stays.
        assertTrue(names.isNotEmpty())
    }
}
