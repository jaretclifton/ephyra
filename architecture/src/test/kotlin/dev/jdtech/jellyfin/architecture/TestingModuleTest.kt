package dev.jdtech.jellyfin.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
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
                configurationsOf(file.readText()).map { module to it }
            }
        val wrong = uses.filterNot { (_, configuration) -> configuration == ALLOWED }
        assertTrue(
            wrong.isEmpty(),
            wrong.joinToString("\n") { (module, configuration) ->
                "$module/build.gradle.kts takes the testing module with `$configuration`. It is " +
                    "for tests: declare it with $ALLOWED."
            },
        )
        assertTrue(
            uses.isNotEmpty(),
            "No module takes the testing module, so the check looks for the wrong text",
        )
    }

    /** The rule reads what it is meant to, however a declaration is written. */
    @Test
    fun `R8 finds the configuration of every way to write a declaration`() {
        val text =
            """
            dependencies {
                // implementation(projects.testing) in a comment does not count
                testImplementation(projects.testing)
                implementation(
                    projects.testing
                )
                testImplementation(projects.data); api(projects.testing)
                "debugImplementation"(project(":testing"))
                add("implementation", projects.testing)
            }
            """
                .trimIndent()

        assertEquals(
            listOf("testImplementation", "implementation", "api", "debugImplementation", "add"),
            configurationsOf(text),
        )
    }

    private companion object {
        const val ALLOWED = "testImplementation"

        /** How a build file names the module: its accessor or its project path. */
        val MARKER = BuildFileDependencies.markerOf("testing")

        /**
         * The configuration of each mention of the module in [buildFile]. A mention through
         * `add("…", …)` gives `add`, which is not allowed either.
         */
        fun configurationsOf(buildFile: String): List<String> =
            BuildFileDependencies.configurationsOf(buildFile, MARKER)
    }
}
