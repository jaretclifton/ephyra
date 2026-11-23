package dev.jdtech.jellyfin.architecture

/** How the build files depend on a module: R8 and R9 read them through this. */
object BuildFileDependencies {

    /**
     * How a build file names the module at [path] (`player/local`): its type-safe accessor
     * (`projects.player.local`, not followed by a further name part) or its project path
     * (`project(":player:local")`).
     */
    fun markerOf(path: String): Regex {
        val accessor = Regex.escape("projects." + path.replace('/', '.'))
        val projectPath = Regex.escape("\":" + path.replace('/', ':') + "\"")
        return Regex("""$accessor(?![\w.])|$projectPath""")
    }

    private val COMMENT = Regex("""//[^\n]*|/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

    /** The name before an opening parenthesis: `implementation`, `"implementation"`, `add`. */
    private val CALL = Regex(""""?([A-Za-z_][A-Za-z0-9_]*)"?\s*$""")

    /**
     * The configuration of each mention of [marker] in [buildFile]: the name of the call whose
     * parentheses hold it, outside `project(…)`, over any line breaks. A mention through `add(…)`
     * gives `add`. Comments do not count.
     */
    fun configurationsOf(buildFile: String, marker: Regex): List<String> {
        val text = COMMENT.replace(buildFile) { " ".repeat(it.value.length) }
        return marker.findAll(text).map { configurationAt(text, it.range.first) }.toList()
    }

    /** Whether [configuration] only reaches test sources (`testImplementation`, …). */
    fun isTestOnly(configuration: String): Boolean =
        configuration.startsWith("test") || configuration.startsWith("androidTest")

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
