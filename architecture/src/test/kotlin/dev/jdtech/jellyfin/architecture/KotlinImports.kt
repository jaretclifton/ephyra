package dev.jdtech.jellyfin.architecture

import com.lemonappdev.konsist.api.Konsist
import java.io.File

/** The imports of Kotlin files, for the rules that hold a module to an allowlist (R19, R20). */
object KotlinImports {
    /**
     * The imports of [files] (path to file), as (path, import name), read by Konsist's Kotlin
     * parser, as R5 reads them: comments, strings and layout do not fool it. A wildcard import ends
     * in `.*`, an alias is left out, backticks are dropped.
     */
    fun of(files: Map<String, File>): List<Pair<String, String>> {
        // Konsist resolves the paths against the root it finds itself.
        val konsistRoot = File(Konsist.projectRootPath)
        val paths = files.entries.associate { (path, file) -> file.canonicalPath to path }
        return Konsist.scopeFromFiles(files.values.map { it.relativeTo(konsistRoot).path })
            .files
            .flatMap { file ->
                val path = paths[File(file.path).canonicalPath] ?: file.path
                file.imports.map {
                    val name = if (it.isWildcard) it.name.removeSuffix(".*") + ".*" else it.name
                    path to name.replace("`", "")
                }
            }
    }
}
