package dev.jdtech.jellyfin.architecture

import com.lemonappdev.konsist.api.Konsist
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Only the Jellyfin adapter may see the Jellyfin SDK
 * (docs/architecture/2-enforcement-and-tests.md, #733). The adapter is the module `server/jellyfin`
 * (#758), and only the apps depend on it (R7). R4, about Room rather than the SDK, allows the whole
 * `data` module. Each baseline lists today's violations and may only shrink.
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

    /** R1. A text check: a type can be used by its full name without an import (`InfoText.kt`). */
    @Test
    fun `R1 no production file outside the adapter mentions the SDK`() {
        val baseline = Baseline("sdk-mentions.txt")
        val violations =
            ProjectTree.productionKotlinFiles
                .filter { (path, file) ->
                    !ProjectTree.inJellyfinAdapter(path) && SDK_PACKAGE in file.readText()
                }
                .keys
        baseline.assertExactly(
            violations,
            unlisted = {
                "$it mentions $SDK outside the Jellyfin adapter. Map the type to one of ours " +
                    "(docs/architecture/3-target.md); do not add the file to ${baseline.path}."
            },
            stale = {
                "$it is in the baseline but no longer mentions the SDK: delete its line from " +
                    "${baseline.path}."
            },
        )
    }

    /**
     * R2. Our own wrapper of the SDK client, which removing the SDK from a module does not stop.
     */
    @Test
    fun `R2 no production file outside the adapter imports JellyfinApi`() {
        val baseline = Baseline("jellyfin-api-imports.txt")
        baseline.assertExactly(
            filesImporting(JELLYFIN_API, allowed = ProjectTree::inJellyfinAdapter),
            unlisted = {
                "$it imports $JELLYFIN_API outside the Jellyfin adapter. Use a port instead " +
                    "(docs/architecture/3-target.md); do not add the file to ${baseline.path}."
            },
            stale = {
                "$it is in the baseline but no longer imports $JELLYFIN_API: delete its line " +
                    "from ${baseline.path}."
            },
        )
    }

    /**
     * R3. A module without the dependency cannot compile against an SDK type at all. A build file
     * declares it through any catalog alias of an SDK library or the Maven group itself; the root
     * build file (`.`) is read too, since it can add the dependency to every module.
     */
    @Test
    fun `R3 only listed modules declare the SDK dependency`() {
        val baseline = Baseline("sdk-modules.txt")
        val violations =
            ProjectTree.moduleBuildFiles
                .filter { (_, file) ->
                    val text = file.readText()
                    sdkDependencyMarkers.any { it in text }
                }
                .keys
        baseline.assertExactly(
            violations,
            unlisted = {
                "$it/build.gradle.kts declares the SDK ($SDK). Only the Jellyfin adapter may " +
                    "depend on the SDK (docs/architecture/3-target.md); do not add the module " +
                    "to ${baseline.path}."
            },
            stale = {
                "$it is in the baseline but its build file no longer declares the SDK: delete " +
                    "its line from ${baseline.path}."
            },
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
                    "instead (docs/architecture/3-target.md)."
            },
        )
        assertTrue(
            APP_MODULES.all { it in dependents },
            "Not every app depends on ${ProjectTree.JELLYFIN_ADAPTER_MODULE}, so the check " +
                "looks for the wrong text: ${dependents.sorted()}",
        )
    }

    /** R4. Room's server DAO stays inside `data`; frozen, not part of the migration. */
    @Test
    fun `R4 no production file outside data imports ServerDatabaseDao`() {
        val baseline = Baseline("dao-imports.txt")
        baseline.assertExactly(
            filesImporting(SERVER_DAO, allowed = ProjectTree::inDataModule),
            unlisted = {
                "$it imports $SERVER_DAO outside the data module. Go through a repository " +
                    "instead; do not add the file to ${baseline.path}."
            },
            stale = {
                "$it is in the baseline but no longer imports $SERVER_DAO: delete its line " +
                    "from ${baseline.path}."
            },
        )
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
