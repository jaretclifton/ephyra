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
 * The baseline (`architecture/baseline/uuid-imports.txt`) lists the files that still do, and may
 * only shrink: the #773 slices empty it down to the files that make random ids.
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
                    "helpers (toItemId(), asString(), see Ids.kt); do not add the file to " +
                    "${baseline.path}."
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
            )
        val notNamed =
            listOf(
                "// import java.util.UUID\nval id = 1\n",
                "/* java.util.UUID */ val id = 1\n",
                "/**\n * Was a [java.util.UUID] before #773.\n */\nval id = 1\n",
                "import java.util.*\n\nval ids = listOf(UUIDs)\n",
                "import java.util.Locale\n\nval id: ItemId? = null // a UUID once\n",
            )

        assertEquals(named.map { true }, named.map(::namesUuid))
        assertEquals(notNamed.map { false }, notNamed.map(::namesUuid))
    }

    private companion object {
        const val UUID = "java.util.UUID"

        /** About 900 today; far fewer means the tree was not found. */
        const val MIN_FILES = 600

        val COMMENT = Regex("""//[^\n]*|/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

        val FULL_NAME = Regex("""\bjava\.util\.UUID\b""")

        val WILDCARD = Regex("""^\s*import\s+java\.util\.\*""", RegexOption.MULTILINE)

        val SIMPLE_NAME = Regex("""\bUUID\b""")

        /**
         * Whether the Kotlin [source] names `java.util.UUID`: by its full name (an import, an alias
         * import or a use), or by `UUID` under a wildcard import of `java.util`. Comments are left
         * out first. A string that mentions it counts too, which is fine for a rule about ids.
         */
        fun namesUuid(source: String): Boolean {
            val code = COMMENT.replace(source, " ")
            return FULL_NAME.containsMatchIn(code) ||
                (WILDCARD.containsMatchIn(code) && SIMPLE_NAME.containsMatchIn(code))
        }
    }
}
