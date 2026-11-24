package dev.jdtech.jellyfin.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R19 (#964). `domain` and `application` are plain Kotlin: their production files import only
 * Kotlin, kotlinx, the JDK and the project's own code, and their build files declare no library but
 * kotlinx. R5 names what they may not use (Android, the SDK, Room); this names what they may, so
 * the next library is caught too, not only the ones R5 thought of.
 *
 * `domain` declared OkHttp for `MediaHttpClient` until #964 moved it into the module `http`;
 * `application` declared paging until #968 moved the library pagers to `modes/film`. The exceptions
 * are in `architecture/baseline/pure-kotlin-imports.txt` and `pure-kotlin-libraries.txt`, each with
 * its issue, and the check is exact: an entry that no longer applies fails until its line is
 * deleted.
 */
class PureKotlinTest {

    @Test
    fun `R19 domain and application import only Kotlin, kotlinx, the JDK and their own code`() {
        // "<module> <import>" to the files that import it.
        val violations =
            MODULES.flatMap { module ->
                    ProjectTree.productionKotlinFiles
                        .filterKeys { it.startsWith("$module/src/") }
                        .also {
                            assertTrue(it.isNotEmpty(), "No production Kotlin file under $module")
                        }
                        .let(::importsOf)
                        .filterNot { (_, import) -> isAllowed(import) }
                        .map { (path, import) -> "$module $import" to path }
                }
                .groupBy({ it.first }, { it.second })
        IMPORTS_BASELINE.assertExactly(
            violations.keys,
            unlisted = {
                "${violations.getValue(it).joinToString()} imports ${it.substringAfter(' ')}. " +
                    "domain and application are plain Kotlin (R19): keep the library in an " +
                    "adapter or a module of its own, behind a port with our own types " +
                    "(docs/architecture/README.md)."
            },
        )
    }

    @Test
    fun `R19 domain and application declare no library but kotlinx`() {
        val violations =
            MODULES.flatMap { module ->
                    val buildFile = ProjectTree.moduleBuildFiles[module]
                    assertTrue(buildFile != null, "$module has no build file")
                    productionLibraries(buildFile.readText())
                        .filterNot { it.startsWith("libs.kotlinx.") }
                        .map { "$module $it" }
                }
                .toSet()
        LIBRARIES_BASELINE.assertExactly(
            violations,
            unlisted = {
                "${it.substringBefore(' ')}/build.gradle.kts declares ${it.substringAfter(' ')}. " +
                    "domain and application are plain Kotlin (R19): a library goes in an " +
                    "adapter or a module of its own (as OkHttp went to http, #964)."
            },
        )
    }

    /**
     * The rule reads every way an import is written (Konsist parses the file as Kotlin), and the
     * allowlist holds what it should.
     */
    @Test
    fun `R19 finds every import outside the allowlist`() {
        val tripleQuote = "\"\"\""
        val text =
            """
            package dev.jdtech.jellyfin.models

            import kotlin.math.roundToInt
            import kotlinx.serialization.Serializable as KSerializable
            import java.time.LocalDateTime
            import javax.crypto.SecretKeyFactory
            import dev.jdtech.jellyfin.domain.ports.ActiveServer
            import okhttp3.OkHttpClient
            import okhttp3.*
            import `androidx`.paging.PagingData
                import okio.Indented
            import /* a comment */ okio.AfterComment
            import kotlin.math.abs; import javax.inject.Inject
            import kotlinxx.Thing
            /*
            import okio.InABlockComment
            */
            // import okio.InALineComment

            val text = ${tripleQuote}
            import okio.InAString
            ${tripleQuote}
            """
                .trimIndent()
        // Under build/, which no rule reads.
        val file =
            ProjectTree.root.resolve("architecture/build/r19-scanner/Fixture.kt").apply {
                parentFile.mkdirs()
                writeText(text)
            }
        assertEquals(
            listOf(
                "okhttp3.OkHttpClient",
                "okhttp3.*",
                "androidx.paging.PagingData",
                "okio.Indented",
                "okio.AfterComment",
                "javax.inject.Inject",
                "kotlinxx.Thing",
            ),
            importsOf(mapOf("fixture" to file)).map { it.second }.filterNot(::isAllowed),
        )
    }

    /** The rule reads libraries in any production configuration, and none of the tests. */
    @Test
    fun `R19 counts every production library and no test one`() {
        val buildFile =
            """
            plugins { alias(libs.plugins.kotlin.jvm) }
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                api(libs.okhttp)
                // implementation(libs.commented.out)
                compileOnly(libs.some.annotations)
                implementation(platform(libs.some.bom))
                testImplementation(platform(libs.junit.bom))
                testImplementation(libs.junit.jupiter)
                implementation(projects.domain)
            }
            """
                .trimIndent()
        assertEquals(
            listOf(
                "libs.kotlinx.coroutines.core",
                "libs.okhttp",
                "libs.some.annotations",
                "libs.some.bom",
            ),
            productionLibraries(buildFile),
        )
    }

    private companion object {
        val MODULES = listOf("domain", "application")

        val IMPORTS_BASELINE = Baseline("pure-kotlin-imports.txt")
        val LIBRARIES_BASELINE = Baseline("pure-kotlin-libraries.txt")

        /**
         * What the production files may import, by package. `javax.crypto` is the JDK's own crypto
         * API (`ProfilePinHasher`); the rest of `javax` is not the JDK (`javax.inject` is Hilt's).
         */
        val ALLOWED_PREFIXES =
            listOf("kotlin.", "kotlinx.", "java.", "javax.crypto.", "dev.jdtech.jellyfin.")

        /** See [KotlinImports.of]: R19 and R20 read imports the same way. */
        fun importsOf(files: Map<String, File>): List<Pair<String, String>> =
            KotlinImports.of(files)

        fun isAllowed(import: String): Boolean = ALLOWED_PREFIXES.any { import.startsWith(it) }

        private val COMMENT = Regex("""//[^\n]*|/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

        /**
         * A library reference in a call: `api(libs.okhttp)`, `implementation(platform(libs.x))`.
         */
        private val LIBRARY = Regex("""([A-Za-z]\w*)\s*\(\s*(?:platform\s*\(\s*)?(libs\.[\w.]+)""")

        /** The catalogue libraries a build file declares outside its test configurations. */
        fun productionLibraries(buildFile: String): List<String> =
            LIBRARY.findAll(COMMENT.replace(buildFile, ""))
                .filter { (it.groupValues[1]) != "alias" }
                .filterNot { BuildFileDependencies.isTestOnly(it.groupValues[1]) }
                .map { it.groupValues[2] }
                .toList()
    }
}
