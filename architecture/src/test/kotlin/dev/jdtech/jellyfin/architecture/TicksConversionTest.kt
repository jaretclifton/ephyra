package dev.jdtech.jellyfin.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R12 (#900). Ticks (100 ns, as Jellyfin and Emby count) are converted by `Ticks` in
 * `domain/.../models/Ticks.kt` and nowhere else: no other production file declares a constant named
 * `TICKS_PER…`, and none multiplies or divides a value whose name contains `ticks` by one of the
 * tick literals (10,000, 10,000,000, 600,000,000, written with or without underscores, `L` or
 * `.0`). No baseline: it starts at zero.
 *
 * The second check goes by names, so it is honest about its reach: `positionMs * 10_000L` has no
 * `ticks` in it and is not found. The first check and the names are what keep a new local constant
 * out.
 */
class TicksConversionTest {

    @Test
    fun `R12 only Ticks declares tick constants and converts a ticks value by a tick literal`() {
        val files = ProjectTree.productionKotlinFiles
        assertTrue(files.size >= MIN_FILES, "Only ${files.size} production Kotlin files found")
        assertTrue(
            TICKS_FILE in files,
            "$TICKS_FILE not found, so the rule looks at the wrong file",
        )

        val violations =
            files
                .filterKeys { it != TICKS_FILE }
                .flatMap { (path, file) -> violations(file.readText()).map { "$path: $it" } }
        assertTrue(
            violations.isEmpty(),
            "Convert ticks with Ticks (domain, models/Ticks.kt):\n" + violations.joinToString("\n"),
        )
    }

    @Test
    fun `R12 finds a constant and a conversion, and nothing else`() {
        val source =
            """
            private const val TICKS_PER_MINUTE = 600_000_000L
            val TICKS_PER_MS = 10_000L
            val a = runtimeTicks / 600000000
            val b = item.playbackPositionTicks.div(10_000L)
            val c = positionTicks.times(10000)
            val d = chapter.startPositionTicks / 10000
            val e = 10_000_000L * seconds.toTicks
            val f = runtimeTicks / 10_000_000.0
            val g = positionMs * 10_000L
            val h = timeout / 10_000
            val i = bitrate * 10_000_000
            val j = ticks / 100_000
            // val k = runtimeTicks / 10000
            val l = "runtimeTicks / 10000"
            val m = Ticks.toMinutes(runtimeTicks)
            val n = ticksRemaining / 10_000L
            val o = 600_000_000L * totalTicksLeft
            val p = runtimeTicks /
                10_000L
            val q = ticksPerFrame / bitrate
            """
                .trimIndent()
        // Constants first, then `ticks <op> literal`, then `literal <op> ticks`.
        assertEquals(
            listOf(
                "declares TICKS_PER_MINUTE",
                "declares TICKS_PER_MS",
                "converts runtimeTicks by 600000000",
                "converts playbackPositionTicks by 10_000L",
                "converts positionTicks by 10000",
                "converts startPositionTicks by 10000",
                "converts runtimeTicks by 10_000_000.0",
                "converts ticksRemaining by 10_000L",
                "converts runtimeTicks by 10_000L",
                "converts toTicks by 10_000_000L",
                "converts totalTicksLeft by 600_000_000L",
            ),
            violations(source),
        )
    }

    @Test
    fun `R12 skips a multiline string`() {
        val quotes = "\"\"\""
        val source = "val s = $quotes\n    runtimeTicks /\n        10_000L\n$quotes\nval t = 1"
        assertEquals(emptyList(), violations(source))
    }

    private companion object {
        /** About 900 today; far fewer means the tree was not found. */
        const val MIN_FILES = 600

        const val TICKS_FILE = "domain/src/main/kotlin/dev/jdtech/jellyfin/models/Ticks.kt"

        const val LITERAL = """(?:10_?000_?000|600_?000_?000|10_?000)(?:\.0)?L?(?![\w.])"""

        /** A triple-quoted string, which may span lines. */
        val RAW_STRING = Regex("\"\"\"[\\s\\S]*?\"\"\"")

        val CONSTANT = Regex("""\b(?:val|var)\s+(TICKS_PER\w*)""")

        val TICKS_BY_LITERAL =
            Regex("""(\w*[Tt]icks\w*)\)?\s*(?:[*/]|\.times\(|\.div\()\s*($LITERAL)""")

        val LITERAL_BY_TICKS = Regex("""(?<![\w.])($LITERAL)\s*[*/]\s*[\w.]*?(\w*[Tt]icks\w*)\b""")

        /**
         * The sanitized source is scanned as a whole, so an operator at a line end still counts.
         */
        fun violations(source: String): List<String> {
            val code =
                UuidBoundaryTest.withoutComments(source)
                    .replace(RAW_STRING, "\"\"")
                    .lines()
                    .joinToString("\n", transform = ::withoutStrings)
            return CONSTANT.findAll(code).map { "declares ${it.groupValues[1]}" }.toList() +
                TICKS_BY_LITERAL.findAll(code).map {
                    "converts ${it.groupValues[1]} by ${it.groupValues[2]}"
                } +
                LITERAL_BY_TICKS.findAll(code).map {
                    "converts ${it.groupValues[2]} by ${it.groupValues[1]}"
                }
        }

        fun withoutStrings(line: String) = line.replace(Regex(""""(?:[^"\\]|\\.)*""""), "\"\"")
    }
}
