package dev.jdtech.jellyfin.architecture

import com.lemonappdev.konsist.api.Konsist
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * R5. The `domain` module is pure Kotlin: the app's own models and the ports
 * (docs/architecture/2-enforcement-and-tests.md, #737). Its production files import nothing from
 * Android, the Jellyfin SDK, Room or our SDK wrapper. No baseline: it starts at zero.
 *
 * The module's build has no Android or SDK library, so most of these would not compile anyway; the
 * rule says why, and still holds if one is added to its build file.
 */
class DomainPurityTest {

    @Test
    fun `R5 the domain module imports nothing from Android, the SDK, Room or the SDK wrapper`() {
        val files = ProjectTree.productionKotlinFiles.filterKeys { it.startsWith(DOMAIN) }
        assertTrue(files.isNotEmpty(), "No production Kotlin file found under $DOMAIN")

        // Konsist resolves the paths against the root it finds itself.
        val konsistRoot = java.io.File(Konsist.projectRootPath)
        val scope = Konsist.scopeFromFiles(files.values.map { it.relativeTo(konsistRoot).path })

        val violations =
            scope.files.flatMap { file ->
                file.imports
                    .map { it.name }
                    .filter(::isForbidden)
                    .map { "${ProjectTree.relativePath(java.io.File(file.path))} imports $it" }
            }
        assertTrue(
            violations.isEmpty(),
            violations.joinToString("\n") {
                "$it. The domain module is pure Kotlin (docs/architecture/3-target.md): map the " +
                    "type to one of ours, or keep the code in its adapter."
            },
        )
    }

    /**
     * Konsist names a wildcard import by its package alone (`dev.jdtech.jellyfin.database`), so the
     * name is compared with a trailing dot.
     */
    private fun isForbidden(import: String): Boolean {
        val name = import.removeSuffix(".*") + "."
        return FORBIDDEN_PREFIXES.any { name.startsWith(it) } &&
            ALLOWED_PREFIXES.none { name.startsWith(it) }
    }

    companion object {
        private const val DOMAIN = "domain/src/"

        /** The SDK package built from parts, as in [SdkBoundaryTest]. */
        private val FORBIDDEN_PREFIXES =
            listOf(
                "android.",
                "androidx.",
                "org.jellyfin" + ".sdk.",
                "dev.jdtech.jellyfin.database.",
                "dev.jdtech.jellyfin.api.",
            )

        /** Paging has a plain JVM artifact (`paging-common`), so the rule leaves it allowed. */
        private val ALLOWED_PREFIXES = listOf("androidx.paging.")
    }
}
