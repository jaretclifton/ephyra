package dev.jdtech.jellyfin.architecture

import java.io.File

/**
 * The files of the repository the rules look at, by their path relative to the project root with
 * forward slashes.
 *
 * Paths are always compared relative to the root: agents work in `.claude/worktrees/<name>/`, so
 * the absolute path of every file contains `.claude`, and a filter on it would drop every file.
 */
object ProjectTree {
    /** The repository root: set by the Gradle test task, else the parent of this module. */
    val root: File =
        (System.getProperty("ephyra.rootDir")?.let(::File) ?: File("..").absoluteFile).normalize()

    /** Top-level folders that are not part of any module's sources. */
    private val ignoredRoots = setOf("buildSrc", ".claude", ".gradle", ".git", ".idea")

    /** Every file under the root, relative path to file, without build outputs. */
    private val allFiles: Map<String, File> by lazy {
        root
            .walkTopDown()
            .onEnter { dir ->
                val path = relativePath(dir)
                path.isEmpty() ||
                    (dir.name != "build" && path.substringBefore('/') !in ignoredRoots)
            }
            .filter { it.isFile }
            .associateBy(::relativePath)
            .toSortedMap()
    }

    /**
     * The Kotlin files of every production source set: a source set whose folder under `src/` does
     * not start with `test` or `androidTest` (`main`, `libre`, `proprietary`, `debug`, `staging`,
     * `libreRelease`, …).
     */
    val productionKotlinFiles: Map<String, File> by lazy {
        allFiles.filter { (path, _) -> path.endsWith(".kt") && isProduction(path) }
    }

    /** The Java files of every production source set. The rules read Kotlin only. */
    val productionJavaFiles: Map<String, File> by lazy {
        allFiles.filter { (path, _) -> path.endsWith(".java") && isProduction(path) }
    }

    /**
     * The build file of every module, by module path (`core`, `app/phone`), and the root build file
     * as `.`: it can add a dependency to every subproject.
     */
    val moduleBuildFiles: Map<String, File> by lazy {
        allFiles
            .filter { (path, _) ->
                path == "build.gradle.kts" || path.endsWith("/build.gradle.kts")
            }
            .mapKeys { (path, _) ->
                path.removeSuffix("build.gradle.kts").removeSuffix("/").ifEmpty { "." }
            }
    }

    /** The version catalog. */
    val versionCatalog: File
        get() = root.resolve("gradle/libs.versions.toml")

    /** Whether the file at [path] belongs to the `data` module. */
    fun inDataModule(path: String): Boolean = path.startsWith("data/")

    /** The module path of the Jellyfin adapter (#758). */
    const val JELLYFIN_ADAPTER_MODULE = "server/jellyfin"

    /**
     * Whether the file at [path] is in the Jellyfin adapter: the module `server/jellyfin` (#758).
     */
    fun inJellyfinAdapter(path: String): Boolean = path.startsWith("$JELLYFIN_ADAPTER_MODULE/")

    fun relativePath(file: File): String =
        file.absoluteFile.normalize().relativeTo(root).path.replace('\\', '/')

    private fun isProduction(path: String): Boolean {
        val sourceSet =
            path.substringAfter("/src/", missingDelimiterValue = "").substringBefore('/')
        return sourceSet.isNotEmpty() &&
            !sourceSet.startsWith("test") &&
            !sourceSet.startsWith("androidTest")
    }
}
