package dev.jdtech.jellyfin.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R14 (#898). The rules of the play state (played, favourite, resume position, last played) are in
 * `PlayState`, in `domain/.../domain/userdata/`, and do not come back to the repository or the
 * sync:
 * - no production file outside `domain` declares a function with the name of one of the rules, the
 *   old ones (`stopUserData`, `serverIsNewer`, `isNewerThanServer`, `keepServerProgress`,
 *   `serverUserDataToStore`) or the new ones (`mergeWithServer`, `playedAfter`,
 *   `refreshedFromServer`, and `PlayState`'s transitions `markedPlayed`, `markedUnplayed`,
 *   `afterUnplayedReport`, `withFavorite`, `progressed`, `stopped`);
 * - the production code of `data` and of `core`'s `work` package does not compare a `lastPlayed…`
 *   value (`<`, `>`, `<=`, `>=`, `compareTo`) or fall back from one (`?:`), and no query there
 *   keeps a date with `COALESCE(lastPlayedDate, …)`: those decide which date wins, which is what
 *   `PlayState` is for.
 *
 * No baseline: it starts at zero. It goes by names and by text, so it is honest about its reach: a
 * comparison of a value named `date` or `playedAt`, or a rule under a new name, is not found. It
 * stops the rules named in #898 from being written again where they were, and the date comparisons
 * the sync and the repository made by hand.
 */
class PlayStateRulesTest {

    @Test
    fun `R14 the play state rules are declared in domain alone, and data and the sync compare no dates`() {
        val files = ProjectTree.productionKotlinFiles
        assertTrue(files.size >= MIN_FILES, "Only ${files.size} production Kotlin files found")
        assertTrue(
            files.any { (path, file) ->
                path.startsWith(PLAY_STATE_DIR) && declaredRules(file.readText()).isNotEmpty()
            },
            "No rule is declared in $PLAY_STATE_DIR, so the rule looks for the wrong names",
        )
        assertTrue(
            SYNC_WORKER in files,
            "$SYNC_WORKER not found, so the rule looks at the wrong files",
        )

        val violations = files.flatMap { (path, file) ->
            val source = file.readText()
            val declared =
                if (path.startsWith("domain/")) emptyList()
                else declaredRules(source).map { "$path declares $it" }
            val compared =
                if (comparesNoDates(path)) dateDecisions(source).map { "$path $it" }
                else emptyList()
            declared + compared
        }
        assertTrue(
            violations.isEmpty(),
            "The play state rules are in PlayState ($PLAY_STATE_DIR): ask it instead.\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun `R14 finds the declarations and the date decisions, and nothing else`() {
        val source =
            """
            internal fun serverIsNewer(local: Long?, server: Long?): Boolean = true
            private suspend fun keepServerProgress(port: Port) {}
            fun PlayState.mergeWithServer(server: PlayState?): SyncMerge = TODO()
            fun <T> stopUserData(a: T) {}
            fun isNewerThanServerAgain() {}
            private fun PlayState.withFavorite(favorite: Boolean) = copy(favorite = favorite)
            fun of(x: Int) = x
            val a = row.lastPlayedDate > server
            val b = local < userData.lastPlayedDate
            val c = lastPlayedMillis ?: now
            val d = lastPlayedAt.compareTo(other)
            val j = (row.lastPlayedDate!! >= other)
            val e = when (x) { lastPlayed -> 1 }
            val f = x -> lastPlayedDate
            // val g = row.lastPlayedDate > server
            val h = "lastPlayedDate > 1"
            val i = lastPlayedDate?.let(::localDateTimeOf)
            "UPDATE userdata SET lastPlayedDate = COALESCE(lastPlayedDate, :now)"
            """
                .trimIndent()
        assertEquals(
            listOf(
                "serverIsNewer",
                "keepServerProgress",
                "mergeWithServer",
                "stopUserData",
                "withFavorite",
            ),
            declaredRules(source),
        )
        assertEquals(
            listOf(
                "keeps a date with COALESCE(lastPlayedDate",
                "compares lastPlayedDate",
                "compares lastPlayedDate",
                "compares lastPlayedMillis",
                "compares lastPlayedAt",
                "compares lastPlayedDate",
            ),
            dateDecisions(source),
        )
    }

    private companion object {
        /** About 900 today; far fewer means the tree was not found. */
        const val MIN_FILES = 600

        const val PLAY_STATE_DIR = "domain/src/main/kotlin/dev/jdtech/jellyfin/domain/userdata/"

        const val SYNC_WORKER = "core/src/main/java/dev/jdtech/jellyfin/work/SyncWorker.kt"

        val RULES =
            listOf(
                "stopUserData",
                "serverIsNewer",
                "isNewerThanServer",
                "keepServerProgress",
                "serverUserDataToStore",
                "mergeWithServer",
                "playedAfter",
                "refreshedFromServer",
                // PlayState's own transitions. `PlayStop.of` is left out: `of` is a common name.
                "markedPlayed",
                "markedUnplayed",
                "afterUnplayedReport",
                "withFavorite",
                "progressed",
                "stopped",
            )

        /** `fun`, type parameters, a receiver, then the name and its parameter list. */
        val DECLARATION = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)\s*\(""")

        /** A `lastPlayed…` name before an ordering operator, `compareTo` or `?:`. */
        val NAME_THEN_OPERATOR =
            Regex("""\b(\w*[lL]astPlayed\w*)(?:!!)?\)?\s*(?:(?<![-=])[<>]=?|\?:|\.compareTo\()""")

        /** An ordering operator (not `->`) before a `lastPlayed…` name. */
        val OPERATOR_THEN_NAME = Regex("""(?<![-=])[<>]=?\s*[\w.]*?\b(\w*[lL]astPlayed\w*)\b""")

        val COALESCE = Regex("""COALESCE\(\s*lastPlayedDate""", RegexOption.IGNORE_CASE)

        fun comparesNoDates(path: String) =
            path.startsWith("data/") ||
                path.startsWith("core/src/main/java/dev/jdtech/jellyfin/work/")

        fun declaredRules(source: String): List<String> =
            DECLARATION.findAll(UuidBoundaryTest.withoutComments(source))
                .map { it.groupValues[1] }
                .filter { it in RULES }
                .toList()

        /** Queries are strings, so the COALESCE check reads them; the comparisons skip strings. */
        fun dateDecisions(source: String): List<String> {
            val withoutComments = UuidBoundaryTest.withoutComments(source)
            val code =
                withoutComments.lines().joinToString("\n") {
                    it.replace(Regex(""""(?:[^"\\]|\\.)*""""), "\"\"")
                }
            return COALESCE.findAll(withoutComments)
                .map { "keeps a date with ${it.value}" }
                .toList() +
                (NAME_THEN_OPERATOR.findAll(code) + OPERATOR_THEN_NAME.findAll(code))
                    .sortedBy { it.range.first }
                    .map { "compares ${it.groupValues[1]}" }
        }
    }
}
