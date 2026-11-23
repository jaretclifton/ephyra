package dev.jdtech.jellyfin.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R9 (#896, ADR 0002 decision 1). `data` is the Room adapter: only the apps, the composition root,
 * depend on it, as only they depend on `server/jellyfin` (R7). The features use the ports of
 * `application` instead. A test configuration (`testImplementation`) is allowed everywhere: a test
 * may build the real repository.
 *
 * The modules that still need Room (`core`, `modes/film`, `setup`) are in an exact baseline,
 * `data-dependents.txt`, which T7 (#899) empties.
 */
class DataDependencyTest {

    @Test
    fun `R9 only the apps depend on data, but for the modules in the baseline`() {
        val dependents =
            dependentsOf(ProjectTree.moduleBuildFiles.mapValues { it.value.readText() })
        assertTrue(
            APP_MODULES.all { it in dependents },
            "Not every app depends on $DATA, so the check looks for the wrong text: " +
                dependents.sorted(),
        )

        val baseline = Baseline("data-dependents.txt")
        baseline.assertExactly(
            dependents - APP_MODULES,
            unlisted = {
                "${buildFile(it)} depends on $DATA. Only ${APP_MODULES.sorted().joinToString(" and ")} " +
                    "may: use the ports of application instead (docs/architecture/" +
                    "5-hexagonal-ddd.md), or a test configuration for a test that needs Room. " +
                    "Do not add the module to ${baseline.path}."
            },
            stale = {
                "$it is in ${baseline.path} but ${buildFile(it)} no longer depends on $DATA " +
                    "outside its tests: delete its line."
            },
        )
    }

    /** The rule reads what it is meant to, however a dependency is written. */
    @Test
    fun `R9 counts every production configuration and no test one`() {
        val files =
            mapOf(
                "a" to "dependencies { implementation(projects.data) }",
                "b" to "dependencies { api(project(\":data\")) }",
                "c" to
                    "dependencies {\n    \"debugImplementation\"(\n        projects.data\n    )\n}",
                "d" to "dependencies { testImplementation(projects.data) }",
                "e" to "dependencies { androidTestImplementation(projects.data) }",
                "f" to "dependencies {\n    // implementation(projects.data)\n}",
                "g" to "dependencies { implementation(projects.dataStore) }",
                "h" to
                    "dependencies { testImplementation(projects.data); add(\"api\", projects.data) }",
            )
        assertEquals(setOf("a", "b", "c", "h"), dependentsOf(files))
    }

    private companion object {
        const val DATA = "data"

        /** The composition roots (as in R7). */
        val APP_MODULES = setOf("app/phone", "app/tv")

        val MARKER = BuildFileDependencies.markerOf(DATA)

        /** The modules, by path, whose build file takes `data` in a configuration not for tests. */
        fun dependentsOf(buildFiles: Map<String, String>): Set<String> =
            buildFiles
                .filter { (_, text) ->
                    BuildFileDependencies.configurationsOf(text, MARKER).any {
                        !BuildFileDependencies.isTestOnly(it)
                    }
                }
                .keys

        fun buildFile(module: String) =
            if (module == ".") "The root build file" else "$module/build.gradle.kts"
    }
}
