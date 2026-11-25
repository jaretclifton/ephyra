package dev.jdtech.jellyfin.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R17 (#918, docs/architecture/5-hexagonal-ddd.md 5.1 point 3). The adapters and the download
 * engine do not read the UI's preferences: no production file of `data`, `server/jellyfin`,
 * `ratings/mdblist` (#331), `device` (#965) or `downloads` names `AppPreferences`
 * (`dev.jdtech.jellyfin.settings.domain.AppPreferences`). What they read of the settings comes
 * through the ports of `application` (`CurrentServerId`, `NextUpMaxDays`, `ResumeThresholdsStore`,
 * `NetworkTimeouts`, `DownloadSettings`, `ExternalRatingsSettings`), which `core` implements
 * (`AppPreferencesPorts.kt`). Tests may build an `AppPreferences`.
 *
 * A text check, as R10: an import, the full name and the simple name all count; comments do not. No
 * baseline.
 */
class AdapterSettingsTest {

    @Test
    fun `R17 no production file of data, server jellyfin, ratings mdblist, device or downloads names AppPreferences`() {
        val files = ProjectTree.productionKotlinFiles
        MODULES.forEach { module ->
            assertTrue(
                files.keys.any { it.startsWith("$module/") },
                "No production Kotlin file in $module, so the rule looks at the wrong files",
            )
        }
        assertTrue(
            files.any { (path, file) -> !inModules(path) && namesAppPreferences(file.readText()) },
            "No production file names AppPreferences at all, so the check looks for the wrong text",
        )

        val violations = files.filter { (path, file) ->
            inModules(path) && namesAppPreferences(file.readText())
        }
        assertTrue(
            violations.isEmpty(),
            violations.keys.joinToString("\n") {
                "$it names AppPreferences. ${MODULES.joinToString()} read the settings through a " +
                    "port of application (CurrentServerId, DownloadSettings, …), which core " +
                    "implements in AppPreferencesPorts.kt; add a port there if one is missing."
            },
        )
    }

    /** The rule reads what it is meant to, however a file names the class. */
    @Test
    fun `R17 finds every way to name the class, and not a comment or a longer name`() {
        val named =
            listOf(
                "import dev.jdtech.jellyfin.settings.domain.AppPreferences\n",
                "import dev.jdtech.jellyfin.settings.domain.*\n\nclass A(p: AppPreferences)\n",
                "val p: dev.jdtech.jellyfin.settings.domain.AppPreferences? = null\n",
                "class A(private val appPreferences: AppPreferences)\n",
            )
        val notNamed =
            listOf(
                "// import dev.jdtech.jellyfin.settings.domain.AppPreferences\nval a = 1\n",
                "/** Read from [AppPreferences] before #918. */\nval a = 1\n",
                "class AppPreferencesDownloadSettings\n",
                "val appPreferences = 1\n",
            )

        assertEquals(named.map { true }, named.map(::namesAppPreferences))
        assertEquals(notNamed.map { false }, notNamed.map(::namesAppPreferences))
    }

    private companion object {
        val MODULES = listOf("data", "server/jellyfin", "ratings/mdblist", "device", "downloads")

        val NAME = Regex("""\bAppPreferences\b""")

        fun inModules(path: String): Boolean = MODULES.any { path.startsWith("$it/") }

        fun namesAppPreferences(source: String): Boolean =
            NAME.containsMatchIn(UuidBoundaryTest.withoutComments(source))
    }
}
