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
        val MARKER = Regex("""projects\.testing\b|":testing"""")

        val COMMENT = Regex("""//[^\n]*|/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

        /** The name before an opening parenthesis: `implementation`, `"implementation"`, `add`. */
        val CALL = Regex(""""?([A-Za-z_][A-Za-z0-9_]*)"?\s*$""")

        /**
         * The configuration of each mention of the module in [buildFile]: the name of the call
         * whose parentheses hold it, outside `project(…)`, over any line breaks. A mention through
         * `add("…", …)` gives `add`, which is not allowed either.
         */
        fun configurationsOf(buildFile: String): List<String> {
            val text = COMMENT.replace(buildFile) { " ".repeat(it.value.length) }
            return MARKER.findAll(text).map { configurationAt(text, it.range.first) }.toList()
        }

        private fun configurationAt(text: String, index: Int): String {
            var depth = 0
            var i = index - 1
            while (i >= 0) {
                when (text[i]) {
                    ')' -> depth++
                    '(' ->
                        if (depth > 0) depth--
                        else {
                            val name = CALL.find(text.substring(0, i))?.groupValues?.get(1) ?: "?"
                            if (name != "project") return name
                        }
                }
                i--
            }
            return "?"
        }
    }
}
