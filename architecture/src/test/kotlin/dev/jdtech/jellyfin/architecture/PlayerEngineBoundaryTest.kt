package dev.jdtech.jellyfin.architecture

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * R6. The player engines of `player/local` stay behind `PlayerEngine`
 * (docs/architecture/2-enforcement-and-tests.md, #764): the ExoPlayer package and the mpv package
 * do not use each other, and no other production file uses either, in any module. The exception is
 * `engine.di`, whose Hilt module binds both. No baseline: it starts at zero.
 *
 * A text check, like R1: a type can be used by its full name without an import. Test sources are
 * out of scope.
 */
class PlayerEngineBoundaryTest {

    @Test
    fun `R6 the engine packages use neither each other nor are used by shared code`() {
        val engineFiles =
            ProjectTree.productionKotlinFiles.values.count {
                packageOf(it.readText()).let { pkg ->
                    pkg != null && (inPackage(pkg, EXO) || inPackage(pkg, MPV))
                }
            }
        assertTrue(engineFiles >= 2, "No production file found in $EXO or $MPV")

        val violations =
            ProjectTree.productionKotlinFiles.flatMap { (path, file) ->
                val text = file.readText()
                val pkg = packageOf(text)
                if (pkg != null && inPackage(pkg, ENGINE_DI)) return@flatMap emptyList()
                listOf(EXO, MPV)
                    .filter { engine ->
                        !(pkg != null && inPackage(pkg, engine)) &&
                            reference(engine).containsMatchIn(text)
                    }
                    .map { engine -> "$path (package $pkg) uses $engine" }
            }
        assertTrue(
            violations.isEmpty(),
            violations.joinToString("\n") {
                "$it. Only the engine itself and $ENGINE_DI may name an engine's package: go " +
                    "through PlayerEngine and EnginePlayer in $ENGINE (docs/areas/" +
                    "architecture.md, recipe 5)."
            },
        )
    }

    private fun packageOf(text: String): String? = PACKAGE.find(text)?.groupValues?.get(1)?.trim()

    private fun inPackage(pkg: String, parent: String) = pkg == parent || pkg.startsWith("$parent.")

    /** The package name followed by anything but a further name part (`engine.mpvx` is not it). */
    private fun reference(pkg: String) = Regex(Regex.escape(pkg) + """(?![\w])""")

    companion object {
        private const val ENGINE = "dev.jdtech.jellyfin.player.local.engine"
        private const val EXO = "$ENGINE.exo"
        private const val MPV = "$ENGINE.mpv"
        private const val ENGINE_DI = "$ENGINE.di"
        private val PACKAGE = Regex("""(?m)^package\s+([\w.]+)""")
    }
}
