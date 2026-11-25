package dev.jdtech.jellyfin.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R15 (#901, docs/architecture/5-hexagonal-ddd.md T9). The download engine and its queue are the
 * module `downloads`, over the ports of `application`:
 * - `downloads` does not depend on `data` (the Room adapter), `server/jellyfin` (the Jellyfin
 *   adapter), `core` (which depends on it and gives it its texts through `DownloadTexts`) or the
 *   apps, outside its tests (as R7 and R9: a test may build the real stores over Room);
 * - no Kotlin file outside `downloads` (and these rules), tests included, names the engine's
 *   implementation: the others use `Downloader`, `DownloadQueue` and `SmartDownloads`. Comments do
 *   not count.
 *
 * No baseline.
 */
class DownloadsModuleTest {

    @Test
    fun `R15 downloads depends on no adapter, not on core and not on an app`() {
        val buildFile = ProjectTree.moduleBuildFiles[DOWNLOADS]
        assertTrue(buildFile != null, "No $DOWNLOADS/build.gradle.kts, so the rule looks elsewhere")
        assertTrue(
            ProjectTree.moduleBuildFiles.keys.containsAll(FORBIDDEN),
            "A module the rule names is gone: ${FORBIDDEN - ProjectTree.moduleBuildFiles.keys}",
        )

        val wrong = forbiddenDependenciesOf(buildFile.readText())
        assertTrue(
            wrong.isEmpty(),
            wrong.joinToString("\n") {
                "$DOWNLOADS/build.gradle.kts depends on $it. The download module uses the ports of " +
                    "application (the download store's and the repository's roles) and DownloadTexts, which core " +
                    "implements; a test may take data with testImplementation."
            },
        )
    }

    /** The rule reads what it is meant to, however a dependency is written. */
    @Test
    fun `R15 counts every production configuration and no test one`() {
        val text =
            """
            dependencies {
                implementation(projects.application)
                // implementation(projects.core) in a comment does not count
                testImplementation(projects.data)
                api(project(":server:jellyfin"))
                "debugImplementation"(projects.core)
                implementation(projects.app.phone)
                implementation(projects.coreUi)
            }
            """
                .trimIndent()

        assertEquals(setOf("server/jellyfin", "core", "app/phone"), forbiddenDependenciesOf(text))
    }

    @Test
    fun `R15 no file outside downloads names the engine's implementation`() {
        val files = ProjectTree.kotlinFiles
        assertTrue(
            ENGINE_FILE in files,
            "$ENGINE_FILE not found, so the rule looks at the wrong files",
        )

        val violations =
            files
                // The rules name the classes they look for.
                .filterKeys { !it.startsWith("$DOWNLOADS/") && !it.startsWith("architecture/") }
                .mapValues { (_, file) -> namedImplementation(file.readText()) }
                .filterValues { it.isNotEmpty() }
        assertTrue(
            violations.isEmpty(),
            violations.entries.joinToString("\n") { (path, names) ->
                "$path names ${names.joinToString()}, the download engine's implementation. Use " +
                    "Downloader, DownloadQueue or SmartDownloads (module downloads)."
            },
        )
    }

    @Test
    fun `R15 finds a name in code, and not in a comment or a longer name`() {
        assertEquals(
            listOf("DownloaderImpl"),
            namedImplementation("import dev.jdtech.jellyfin.utils.DownloaderImpl"),
        )
        assertEquals(
            listOf("MediaDownloadEngine"),
            namedImplementation("val e = MediaDownloadEngine(client, connectivity)"),
        )
        assertEquals(emptyList(), namedImplementation("// built by DownloaderImpl"))
        assertEquals(emptyList(), namedImplementation("/** see [MediaDownloadEngine] */ val x = 1"))
        assertEquals(emptyList(), namedImplementation("class DownloaderImplTest"))
    }

    private companion object {
        const val DOWNLOADS = "downloads"

        const val ENGINE_FILE =
            "downloads/src/main/java/dev/jdtech/jellyfin/utils/download/MediaDownloadEngine.kt"

        /** The modules `downloads` must not depend on outside its tests. */
        val FORBIDDEN = setOf("data", "server/jellyfin", "core", "app/phone", "app/tv")

        /** The engine's implementation: the module builds it; nothing else names it. */
        val IMPLEMENTATION =
            listOf(
                "DownloaderImpl",
                "MediaDownloadEngine",
                "ResumeValidatorStore",
                "SharedPreferencesResumeValidatorStore",
                "DownloadHttpClient",
                "ImagesDownloaderWorker",
            )

        fun forbiddenDependenciesOf(buildFile: String): Set<String> =
            FORBIDDEN.filter { module ->
                    BuildFileDependencies.configurationsOf(
                            buildFile,
                            BuildFileDependencies.markerOf(module),
                        )
                        .any { !BuildFileDependencies.isTestOnly(it) }
                }
                .toSet()

        fun namedImplementation(source: String): List<String> {
            val code = UuidBoundaryTest.withoutComments(source)
            return IMPLEMENTATION.filter { Regex("""\b$it\b""").containsMatchIn(code) }
        }
    }
}
