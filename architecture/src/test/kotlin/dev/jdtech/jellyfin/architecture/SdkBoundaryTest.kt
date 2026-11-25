package dev.jdtech.jellyfin.architecture

import com.lemonappdev.konsist.api.Konsist
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Only the Jellyfin adapter may see the Jellyfin SDK
 * (docs/architecture/2-enforcement-and-tests.md, #733). The adapter is the module `server/jellyfin`
 * (#758), and only the apps depend on it (R7). R1 to R3 and R7 have no exceptions (#768). R4, about
 * Room rather than the SDK, allows the whole `data` module and none outside it (#899).
 *
 * Test sources of every module are out of scope.
 */
class SdkBoundaryTest {

    @Test
    fun `the rules see the production sources`() {
        val files = ProjectTree.productionKotlinFiles.size
        assertTrue(files >= MIN_PRODUCTION_FILES, "Only $files production Kotlin files found")
        val parsed = konsistScope.files.size
        assertTrue(parsed >= MIN_PRODUCTION_FILES, "Konsist parsed only $parsed files")
        assertTrue(ProjectTree.moduleBuildFiles.size >= 10, "Module build files not found")
        assertTrue("." in ProjectTree.moduleBuildFiles, "The root build file not found")
        assertTrue(
            sdkDependencyMarkers.size >= 2,
            "No SDK library found in ${ProjectTree.versionCatalog}",
        )
    }

    /**
     * R1, R2 and R4 read Kotlin files only. There is no production Java file outside the adapter;
     * this keeps it that way rather than letting one pass the rules unread.
     */
    @Test
    fun `no production Java file outside the adapter`() {
        val java = ProjectTree.productionJavaFiles.keys.filterNot(ProjectTree::inJellyfinAdapter)
        assertTrue(
            java.isEmpty(),
            java.joinToString("\n") {
                "$it is a production Java file outside the Jellyfin adapter, which the " +
                    "architecture rules do not read. Write it in Kotlin, or extend " +
                    "SdkBoundaryTest to read Java first."
            },
        )
    }

    /**
     * R1. A text check: a type can be used by its full name without an import (`InfoText.kt`).
     * Comments do not count, as in R10 and R17.
     */
    @Test
    fun `R1 no production file outside the adapter mentions the SDK`() {
        val violations =
            ProjectTree.productionKotlinFiles
                .filter { (path, file) ->
                    !ProjectTree.inJellyfinAdapter(path) &&
                        SDK_PACKAGE in UuidBoundaryTest.withoutComments(file.readText())
                }
                .keys
        assertNone(violations) {
            "$it mentions $SDK outside the Jellyfin adapter (${ProjectTree.JELLYFIN_ADAPTER_MODULE})." +
                " Map the type to one of ours ($TARGET_DOC)."
        }
    }

    /**
     * R2. Our own wrapper of the SDK client, which removing the SDK from a module does not stop.
     */
    @Test
    fun `R2 no production file outside the adapter imports JellyfinApi`() {
        assertNone(filesImporting(JELLYFIN_API, allowed = ProjectTree::inJellyfinAdapter)) {
            "$it imports $JELLYFIN_API outside the Jellyfin adapter " +
                "(${ProjectTree.JELLYFIN_ADAPTER_MODULE}). Use a port instead ($TARGET_DOC)."
        }
    }

    /**
     * R3. A module without the dependency cannot compile against an SDK type at all. A build file
     * declares it through any catalog alias of an SDK library or the Maven group itself, in any
     * configuration (`debugImplementation` too); the root build file (`.`) is read as well, since
     * it can add the dependency to every module. Exactly the adapter module declares it.
     */
    @Test
    fun `R3 only the Jellyfin adapter module declares the SDK dependency`() {
        val declaring =
            ProjectTree.moduleBuildFiles
                .filter { (_, file) ->
                    val text = file.readText()
                    sdkDependencyMarkers.any { it in text }
                }
                .keys
        assertNone(declaring - ProjectTree.JELLYFIN_ADAPTER_MODULE) {
            "${if (it == ".") "The root build file" else "$it/build.gradle.kts"} declares the " +
                "SDK ($SDK). Only ${ProjectTree.JELLYFIN_ADAPTER_MODULE} may depend on it: use a " +
                "port from domain instead ($TARGET_DOC)."
        }
        assertTrue(
            ProjectTree.JELLYFIN_ADAPTER_MODULE in declaring,
            "${ProjectTree.JELLYFIN_ADAPTER_MODULE}/build.gradle.kts does not declare the SDK, " +
                "so the check looks for the wrong text: $sdkDependencyMarkers",
        )
    }

    /**
     * R7 (#758). The apps are the composition root: no other module depends on the adapter module,
     * so no feature module reaches into it and an Emby adapter can sit next to it. No baseline.
     */
    @Test
    fun `R7 only the apps depend on the Jellyfin adapter module`() {
        val markers = adapterDependencyMarkers(ProjectTree.JELLYFIN_ADAPTER_MODULE)
        val dependents =
            ProjectTree.moduleBuildFiles
                .filter { (_, file) ->
                    val text = file.readText()
                    markers.any { it in text }
                }
                .keys
        val others = (dependents - APP_MODULES).sorted()
        assertTrue(
            others.isEmpty(),
            others.joinToString("\n") {
                "$it/build.gradle.kts depends on ${ProjectTree.JELLYFIN_ADAPTER_MODULE}. Only " +
                    "${APP_MODULES.sorted().joinToString(" and ")} may: use a port from domain " +
                    "instead (${TARGET_DOC})."
            },
        )
        assertTrue(
            APP_MODULES.all { it in dependents },
            "Not every app depends on ${ProjectTree.JELLYFIN_ADAPTER_MODULE}, so the check " +
                "looks for the wrong text: ${dependents.sorted()}",
        )
    }

    /**
     * R4. Room's server DAO stays inside `data`. No exceptions since #899 emptied its baseline: the
     * features use the store ports of `application` (the roles of the server and download
     * stores, #967, and `PendingUserDataStore`), and `data`'s own Hilt modules build Room.
     */
    @Test
    fun `R4 no production file outside data imports ServerDatabaseDao`() {
        assertNone(filesImporting(SERVER_DAO, allowed = ProjectTree::inDataModule)) {
            "$it imports $SERVER_DAO outside the data module. Use a store port of application " +
                "(StoredServers, StoredUsers, DownloadList, DownloadSources, …, PendingUserDataStore) " +
                "instead."
        }
    }

    /** Fails with one [message] line per violation, sorted, when there is any. */
    private fun assertNone(violations: Set<String>, message: (String) -> String) {
        if (violations.isNotEmpty())
            fail(violations.sorted().joinToString("\n", transform = message))
    }

    /**
     * Files that import [name] by its name or through a wildcard import of its package, but for
     * those where it is [allowed].
     */
    private fun filesImporting(name: String, allowed: (String) -> Boolean): Set<String> =
        konsistScope.files
            .filter { file ->
                file.imports.any {
                    it.name == name ||
                        (it.isWildcard &&
                            it.name.removeSuffix(".*") == name.substringBeforeLast('.'))
                }
            }
            .map { ProjectTree.relativePath(java.io.File(it.path)) }
            .filterNot(allowed)
            .toSet()

    companion object {
        /** Built from parts, so this module never shows up in a search of the tree for it. */
        private const val SDK = "org.jellyfin" + ".sdk"
        private const val SDK_PACKAGE = "$SDK."
        private const val JELLYFIN_API = "dev.jdtech.jellyfin.api.JellyfinApi"
        private const val SERVER_DAO = "dev.jdtech.jellyfin.database.ServerDatabaseDao"
        private const val TARGET_DOC = "docs/areas/architecture.md, recipe 5"

        /** The composition roots, the only modules that may depend on a server adapter (R7). */
        private val APP_MODULES = setOf("app/phone", "app/tv")

        /**
         * How a build file names the module at [path] (`server/jellyfin`): the type-safe accessor
         * (`projects.server.jellyfin`) or the project path (`project(":server:jellyfin")`).
         */
        private fun adapterDependencyMarkers(path: String): Set<String> =
            setOf("projects." + path.replace('/', '.'), "\":" + path.replace('/', ':') + "\"")

        /**
         * What marks an SDK dependency in a build file: the Maven group, and the accessor of every
         * catalog library in that group (`jellyfin-core = { group = "<sdk>", … }` gives
         * `libs.jellyfin.core`), so a new alias is caught too.
         */
        private val sdkDependencyMarkers: Set<String> by lazy {
            val aliases =
                ProjectTree.versionCatalog
                    .readLines()
                    .filter { "\"$SDK\"" in it || "\"$SDK:" in it }
                    .map {
                        "libs." + it.substringBefore('=').trim().replace('-', '.').replace('_', '.')
                    }
            setOf(SDK) + aliases
        }

        /** About 900 today; far fewer means the tree was not found. */
        private const val MIN_PRODUCTION_FILES = 600

        /**
         * The production files, parsed once. The list is built from [ProjectTree] rather than
         * `Konsist.scopeFromProject()`, which would also parse every copy of the repository under
         * `.claude/worktrees/` when run from the main checkout.
         */
        private val konsistScope by lazy {
            // Konsist resolves the paths against the root it finds itself.
            val konsistRoot = java.io.File(Konsist.projectRootPath)
            Konsist.scopeFromFiles(
                ProjectTree.productionKotlinFiles.values.map { it.relativeTo(konsistRoot).path }
            )
        }
    }
}
