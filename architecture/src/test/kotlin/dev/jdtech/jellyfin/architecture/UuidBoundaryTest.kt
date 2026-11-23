package dev.jdtech.jellyfin.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R10 (#773). Ids are the domain's own (`ItemId`, `UserId`, `ServerId`, `MediaSourceId` in
 * `domain/.../models/Ids.kt`), not `java.util.UUID`: that is how the Jellyfin SDK names them, and
 * another server names them otherwise. Only the Jellyfin adapter (`server/jellyfin`) converts
 * between the two, so no other production file imports or names `java.util.UUID`.
 *
 * A text check, as R1: a file can name the class by its full name without an import, or import
 * `java.util.*` and say `UUID`. Comments do not count.
 *
 * Since the flip to value classes (#773), `architecture/baseline/uuid-imports.txt` is an allowlist,
 * not debt: the files that name `UUID` for an id the app makes itself (server addresses, stored
 * media streams, random names, preview dummies), grouped under the reason. An item or user id is
 * never a reason. The check stays exact both ways, so a file that stops naming `UUID` loses its
 * line.
 */
class UuidBoundaryTest {

    @Test
    fun `R10 no production file outside the adapter imports or names java util UUID`() {
        val files =
            ProjectTree.productionKotlinFiles.filterKeys { !ProjectTree.inJellyfinAdapter(it) }
        assertTrue(files.size >= MIN_FILES, "Only ${files.size} production Kotlin files found")
        assertTrue(
            ProjectTree.productionKotlinFiles.any { (path, file) ->
                ProjectTree.inJellyfinAdapter(path) && namesUuid(file.readText())
            },
            "No file of the adapter names $UUID, so the check looks for the wrong text",
        )

        val baseline = Baseline("uuid-imports.txt")
        baseline.assertExactly(
            files.filterValues { namesUuid(it.readText()) }.keys,
            unlisted = {
                "$it imports or names $UUID outside the Jellyfin adapter " +
                    "(${ProjectTree.JELLYFIN_ADAPTER_MODULE}). Use the domain's ids and their " +
                    "helpers (toItemId(), asString(), see Ids.kt). Only a random id of the app's " +
                    "own, of a kind ${baseline.path} gives a reason for, belongs in that list."
            },
            stale = {
                "$it is in the baseline but no longer names $UUID: delete its line from " +
                    "${baseline.path}."
            },
        )
    }

    /** The rule reads what it is meant to, however a file names the class. */
    @Test
    fun `R10 finds every way to name the class`() {
        val named =
            listOf(
                "import java.util.UUID\n",
                "import java.util.UUID as Id\n",
                "val id = java.util.UUID.randomUUID()\n",
                "import java.util.*\n\nval id: UUID? = null\n",
                "val url = \"https://example.test/\${java.util.UUID.randomUUID()}\"\n",
                "val raw = \"\"\"a // b\"\"\" + java.util.UUID.randomUUID()\n",
                "val s = \"/* not a comment\" + java.util.UUID.randomUUID() + \"*/\"\n",
                "/* outer /* inner */ \" */ val id = java.util.UUID.randomUUID()\n",
            )
        val notNamed =
            listOf(
                "// import java.util.UUID\nval id = 1\n",
                "/* java.util.UUID */ val id = 1\n",
                "/**\n * Was a [java.util.UUID] before #773.\n */\nval id = 1\n",
                "import java.util.*\n\nval ids = listOf(UUIDs)\n",
                "import java.util.Locale\n\nval id: ItemId? = null // a UUID once\n",
                "val q = '\"' // java.util.UUID\n",
                "fun `a movie's id`() = 1 // java.util.UUID\n",
                "/* outer /* inner */ java.util.UUID */ val id = 1\n",
            )

        assertEquals(named.map { true }, named.map(::namesUuid))
        assertEquals(notNamed.map { false }, notNamed.map(::namesUuid))
    }

    private companion object {
        const val UUID = "java.util.UUID"

        /** About 900 today; far fewer means the tree was not found. */
        const val MIN_FILES = 600

        val FULL_NAME = Regex("""\bjava\.util\.UUID\b""")

        val WILDCARD = Regex("""^\s*import\s+java\.util\.\*""", RegexOption.MULTILINE)

        val SIMPLE_NAME = Regex("""\bUUID\b""")

        /**
         * Whether the Kotlin [source] names `java.util.UUID`: by its full name (an import, an alias
         * import or a use), or by `UUID` under a wildcard import of `java.util`. Comments are left
         * out first. A string that mentions it counts too, which is fine for a rule about ids.
         */
        fun namesUuid(source: String): Boolean {
            val code = withoutComments(source)
            return FULL_NAME.containsMatchIn(code) ||
                (WILDCARD.containsMatchIn(code) && SIMPLE_NAME.containsMatchIn(code))
        }

        /**
         * [source] with its `//` and `/* */` comments blanked out, string literals kept as they
         * are: a `//` inside `"https://…"` starts no comment. A quote inside a template
         * (`"${f("x")}"`) ends and reopens the literal, which keeps all of its text either way.
         */
        fun withoutComments(source: String): String {
            val out = StringBuilder(source.length)
            var i = 0
            fun at(text: String) = source.startsWith(text, i)
            while (i < source.length) {
                when {
                    at("//") -> {
                        val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                        out.append(" ")
                        i = end
                    }
                    // Kotlin's block comments nest: the comment ends at the */ of its own /*.
                    at("/*") -> {
                        var depth = 0
                        while (i < source.length) {
                            when {
                                at("/*") -> {
                                    depth++
                                    i += 2
                                }
                                at("*/") -> {
                                    depth--
                                    i += 2
                                    if (depth == 0) break
                                }
                                else -> i++
                            }
                        }
                        out.append(" ")
                    }
                    at("\"\"\"") -> {
                        val end =
                            source.indexOf("\"\"\"", i + 3).let {
                                if (it < 0) source.length else it + 3
                            }
                        out.append(source, i, end)
                        i = end
                    }
                    at("\"") -> {
                        var j = i + 1
                        while (j < source.length && source[j] != '"' && source[j] != '\n') {
                            j += if (source[j] == '\\') 2 else 1
                        }
                        val end = minOf(j + 1, source.length)
                        out.append(source, i, end)
                        i = end
                    }
                    // A name in backticks may hold an apostrophe or a quote.
                    at("`") -> {
                        val end = source.indexOf('`', i + 1).let { if (it < 0) i + 1 else it + 1 }
                        out.append(source, i, end)
                        i = end
                    }
                    // A character literal: '"' starts no string.
                    at("'") -> {
                        var j = i + 1
                        if (j < source.length && source[j] == '\\') j++
                        val end = source.indexOf('\'', j + 1).let { if (it < 0) i + 1 else it + 1 }
                        out.append(source, i, end)
                        i = end
                    }
                    else -> out.append(source[i++])
                }
            }
            return out.toString()
        }
    }
}
