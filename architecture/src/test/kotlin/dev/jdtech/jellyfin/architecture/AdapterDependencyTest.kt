package dev.jdtech.jellyfin.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R18 (#733). An adapter implements the ports of `domain` and `application` and knows nothing of
 * the features that use them: `data`, `server/jellyfin`, `ratings/mdblist` and `device` (#965)
 * depend on no feature module. What an adapter needs from the settings it reads through a port
 * (`NetworkTimeouts`, R17). A test configuration is allowed: an adapter's test may build a
 * feature's class.
 *
 * `server/jellyfin` depended on `settings` for the three default timeouts until they moved to
 * `NetworkTimeouts`.
 */
class AdapterDependencyTest {

    @Test
    fun `R18 no adapter module depends on a feature module`() {
        val buildFiles = ProjectTree.moduleBuildFiles.mapValues { it.value.readText() }
        assertTrue(
            ADAPTER_MODULES.all { it in buildFiles } && FEATURE_MODULES.all { it in buildFiles },
            "A module of the rule is gone, so it checks nothing for it: " +
                ((ADAPTER_MODULES + FEATURE_MODULES) - buildFiles.keys).sorted(),
        )

        val found = featureDependencies(buildFiles.filterKeys { it in ADAPTER_MODULES })
        assertTrue(
            found.isEmpty(),
            found.joinToString("\n") { (adapter, feature) ->
                "$adapter/build.gradle.kts depends on $feature. An adapter depends on domain and " +
                    "application only: read a setting through a port of application, as " +
                    "NetworkTimeouts does (docs/architecture/README.md)."
            },
        )
    }

    /** The rule reads what it is meant to, however a dependency is written. */
    @Test
    fun `R18 counts every production configuration and no test one`() {
        val files =
            mapOf(
                "a" to "dependencies { implementation(projects.settings) }",
                "b" to "dependencies { api(project(\":modes:film\")) }",
                "c" to "dependencies { testImplementation(projects.settings) }",
                "d" to "dependencies {\n    // implementation(projects.core)\n}",
                "e" to "dependencies { implementation(projects.application) }",
                "f" to "dependencies { implementation(projects.player.local) }",
            )
        assertEquals(
            listOf("a" to "settings", "b" to "modes/film", "f" to "player/local"),
            featureDependencies(files),
        )
    }

    private companion object {
        val ADAPTER_MODULES = setOf("data", "server/jellyfin", "ratings/mdblist", "device")

        /** The feature layer, as docs/architecture/modules.md has it. */
        val FEATURE_MODULES =
            setOf(
                "core",
                "downloads",
                "modes/film",
                "player/cast",
                "player/core",
                "player/local",
                "settings",
                "setup",
            )

        /**
         * Each (module, feature) where the build file of the module takes the feature in a
         * configuration not for tests, in the order of the modules and then the features.
         */
        fun featureDependencies(buildFiles: Map<String, String>): List<Pair<String, String>> =
            buildFiles.keys.sorted().flatMap { module ->
                FEATURE_MODULES.sorted()
                    .filter { feature ->
                        BuildFileDependencies.configurationsOf(
                                buildFiles.getValue(module),
                                BuildFileDependencies.markerOf(feature),
                            )
                            .any { !BuildFileDependencies.isTestOnly(it) }
                    }
                    .map { module to it }
            }
    }
}
