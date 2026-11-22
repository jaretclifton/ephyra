package dev.jdtech.jellyfin.architecture

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * R8 (#771). The `testing` module (FakeMediaServer and the contract suites) is for tests only: a
 * module takes it with `testImplementation`, so it is on no app's runtime classpath. No baseline.
 */
class TestingModuleTest {

    @Test
    fun `R8 the testing module is only a test dependency`() {
        val uses =
            ProjectTree.moduleBuildFiles.flatMap { (module, file) ->
                file
                    .readLines()
                    .filter { line -> !line.trim().startsWith("//") && MARKERS.any { it in line } }
                    .map { module to it }
            }
        val wrong = uses.filterNot { (_, line) -> line.trim().startsWith("testImplementation(") }
        assertTrue(
            wrong.isEmpty(),
            wrong.joinToString("\n") { (module, line) ->
                "$module/build.gradle.kts: `${line.trim()}`. The testing module is for tests: " +
                    "declare it with testImplementation."
            },
        )
        assertTrue(
            uses.isNotEmpty(),
            "No module takes the testing module, so the check looks for the wrong text",
        )
    }

    private companion object {
        /** How a build file names the module: its accessor or its project path. */
        val MARKERS = setOf("projects.testing)", "\":testing\"")
    }
}
