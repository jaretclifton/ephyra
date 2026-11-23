package dev.jdtech.jellyfin.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R13 (#897). Whether a download is finished is decided by the name of its file: a download is
 * written as `<path>.download` and renamed when it is done. `DownloadState.kt` in `domain` is the
 * only production file that names that suffix: every other file asks `ItemSource.downloadState` or
 * `DownloadFiles`. No baseline: it starts at zero.
 *
 * The check looks for a string literal that ends in `.download` (`".download"`, `"$path.download"`)
 * in the code, with comments removed. Tests are not production files and may spell the name out.
 */
class DownloadSuffixTest {

    @Test
    fun `R13 only DownloadState names the partial download suffix`() {
        val files = ProjectTree.productionKotlinFiles
        assertTrue(files.size >= MIN_FILES, "Only ${files.size} production Kotlin files found")
        assertTrue(
            DOWNLOAD_STATE_FILE in files,
            "$DOWNLOAD_STATE_FILE not found, so the rule looks at the wrong file",
        )

        val violations =
            files
                .filterKeys { it != DOWNLOAD_STATE_FILE }
                .filterValues { SUFFIX_LITERAL.containsMatchIn(it.readText().let(::code)) }
                .keys
        assertTrue(
            violations.isEmpty(),
            "Use ItemSource.downloadState or DownloadFiles (domain, models/DownloadState.kt) " +
                "instead of the \".download\" suffix:\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun `R13 finds a literal that ends in the suffix, and nothing else`() {
        assertTrue(SUFFIX_LITERAL.containsMatchIn(code("""val a = path.endsWith(".download")""")))
        assertTrue(SUFFIX_LITERAL.containsMatchIn(code("""val b = "downloads/${'$'}x.download"""")))
        assertTrue(SUFFIX_LITERAL.containsMatchIn(code("""val c = p.removeSuffix(".download")""")))
        assertEquals(false, SUFFIX_LITERAL.containsMatchIn(code("// path.endsWith(\".download\")")))
        assertEquals(false, SUFFIX_LITERAL.containsMatchIn(code("/* \".download\" */ val d = 1")))
        assertEquals(false, SUFFIX_LITERAL.containsMatchIn(code("""val e = "downloads/x"""")))
        assertEquals(false, SUFFIX_LITERAL.containsMatchIn(code("""val f = R.string.download""")))
    }

    private companion object {
        /** About 900 today; far fewer means the tree was not found. */
        const val MIN_FILES = 600

        const val DOWNLOAD_STATE_FILE =
            "domain/src/main/kotlin/dev/jdtech/jellyfin/models/DownloadState.kt"

        val SUFFIX_LITERAL = Regex("""\.download"""")

        fun code(source: String) = UuidBoundaryTest.withoutComments(source)
    }
}
