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
        val violations = importsOf(DOMAIN).filter { (_, import) -> isForbidden(import) }
        assertTrue(
            violations.isEmpty(),
            violations.joinToString("\n") { (path, import) ->
                "$path imports $import. The domain module is pure Kotlin " +
                    "(docs/areas/architecture.md, recipe 5): map the type to one of ours, or keep the " +
                    "code in its adapter."
            },
        )
    }

    /**
     * R5 for `application` (#896): the same, and nothing that `data` declares either (its entities
     * share the package `dev.jdtech.jellyfin.models` with domain's models, so a package prefix
     * would not catch them). A wildcard import of a package `data` declares something in is a
     * violation too, `dev.jdtech.jellyfin.models.*` included: import domain's models by name. The
     * ports the features use stay free of the Room adapter.
     */
    @Test
    fun `R5 the application module imports nothing from Android, the SDK, Room or data`() {
        val dataNames = productionDeclarationsOf(DATA)
        assertTrue(
            "dev.jdtech.jellyfin.database.ServerDatabaseDao" in dataNames,
            "The declarations of $DATA were not read: $dataNames",
        )
        val dataPackages = dataNames.map { it.substringBeforeLast('.') }.toSet()
        val violations =
            importsOf(APPLICATION).filter { (_, import) ->
                val name = import.removeSuffix(".*")
                isForbidden(import) ||
                    if (import.endsWith(".*")) name in dataPackages else name in dataNames
            }
        assertTrue(
            violations.isEmpty(),
            violations.joinToString("\n") { (path, import) ->
                "$path imports $import. The application module is pure Kotlin and knows " +
                    "nothing of Room (ADR 0002): name a type of domain, and map it in data."
            },
        )
    }

    /**
     * The imports of the production Kotlin files under [prefix], as (file, import name). A wildcard
     * import ends in `.*` (Konsist names it by its package alone).
     */
    private fun importsOf(prefix: String): List<Pair<String, String>> =
        scopeOf(prefix).files.flatMap { file ->
            val path = ProjectTree.relativePath(java.io.File(file.path))
            file.imports.map {
                path to if (it.isWildcard) it.name.removeSuffix(".*") + ".*" else it.name
            }
        }

    /**
     * The full names of what the production files under [prefix] declare at their top level:
     * classes, interfaces, objects, type aliases, functions and properties.
     */
    private fun productionDeclarationsOf(prefix: String): Set<String> =
        scopeOf(prefix)
            .files
            .flatMap { file ->
                val pkg = file.packagee?.name?.let { "$it." } ?: ""
                // false: neither nested nor local declarations.
                (file.classes(false, false).map { it.name } +
                        file.interfaces(false).map { it.name } +
                        file.objects(false).map { it.name } +
                        file.typeAliases.map { it.name } +
                        file.functions(false, false).map { it.name } +
                        file.properties(false).map { it.name })
                    .map { pkg + it }
            }
            .toSet()

    private fun scopeOf(prefix: String) =
        ProjectTree.productionKotlinFiles
            .filterKeys { it.startsWith(prefix) }
            .also { assertTrue(it.isNotEmpty(), "No production Kotlin file found under $prefix") }
            .let { files ->
                // Konsist resolves the paths against the root it finds itself.
                val konsistRoot = java.io.File(Konsist.projectRootPath)
                Konsist.scopeFromFiles(files.values.map { it.relativeTo(konsistRoot).path })
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
        private const val APPLICATION = "application/src/"
        private const val DATA = "data/src/"

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
