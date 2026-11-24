package dev.jdtech.jellyfin.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R20 (#965). `data` is the Room adapter and nothing else: of Android, its production files import
 * only Room (`androidx.room3`), SQLite (`androidx.sqlite`) and `android.content.Context`, which the
 * database is built with. Hilt is `dagger` and `javax.inject`, not Android. What asks the device
 * (the codecs, the display, the network) is an adapter of its own, `device`, so `android.media` or
 * `android.net` in `data` fails here.
 *
 * The codec probe was in `data` until #965 moved it to `device`. The exceptions are in
 * `architecture/baseline/room-adapter-imports.txt`, each with its issue, and the check is exact: an
 * entry that no longer applies fails until its line is deleted.
 */
class RoomAdapterTest {

    @Test
    fun `R20 data imports nothing of Android but Room, SQLite and Context`() {
        val files = ProjectTree.productionKotlinFiles.filterKeys { it.startsWith("$DATA/src/") }
        assertTrue(files.isNotEmpty(), "No production Kotlin file under $DATA")
        val imports = KotlinImports.of(files)
        assertTrue(
            imports.any { (_, import) -> import.startsWith("androidx.room3.") },
            "No file of $DATA imports Room, so the rule reads the wrong files",
        )

        // Import to the files that import it.
        val violations =
            imports
                .filter { (_, import) -> isAndroid(import) && !isAllowed(import) }
                .groupBy({ it.second }, { it.first })
        BASELINE.assertExactly(
            violations.keys,
            unlisted = {
                "${violations.getValue(it).joinToString()} imports $it. data is Room and " +
                    "nothing else (R20): what asks the device goes in the module device, behind " +
                    "a port of domain or application (docs/architecture/README.md)."
            },
        )
    }

    /** The rule reads every way an import is written, and the allowlist holds what it should. */
    @Test
    fun `R20 finds every Android import outside the allowlist`() {
        val tripleQuote = "\"\"\""
        val text =
            """
            package dev.jdtech.jellyfin.database

            import android.content.Context
            import androidx.room3.Entity
            import androidx.sqlite.SQLiteConnection
            import dagger.hilt.android.qualifiers.ApplicationContext
            import javax.inject.Singleton
            import android.media.MediaFormat
            import android.content.ContextWrapper
            import android.content.*
            import `android`.net.ConnectivityManager
            import androidx.paging.PagingSource as Source
                import android.view.Display
            import androidx.room3x.Thing
            // import android.os.Build
            /*
            import android.util.Range
            */

            val text = ${tripleQuote}
            import android.app.Application
            ${tripleQuote}
            """
                .trimIndent()
        // Under build/, which no rule reads.
        val file =
            ProjectTree.root.resolve("architecture/build/r20-scanner/Fixture.kt").apply {
                parentFile.mkdirs()
                writeText(text)
            }
        assertEquals(
            listOf(
                "android.media.MediaFormat",
                "android.content.ContextWrapper",
                "android.content.*",
                "android.net.ConnectivityManager",
                "androidx.paging.PagingSource",
                "android.view.Display",
                "androidx.room3x.Thing",
            ),
            KotlinImports.of(mapOf("fixture" to file))
                .map { it.second }
                .filter { isAndroid(it) && !isAllowed(it) },
        )
    }

    private companion object {
        const val DATA = "data"

        val BASELINE = Baseline("room-adapter-imports.txt")

        /** Room and SQLite, by package. */
        val ALLOWED_PREFIXES = listOf("androidx.room3.", "androidx.sqlite.")

        /** Single classes: the context the database and the image files are opened with. */
        val ALLOWED_NAMES = setOf("android.content.Context")

        fun isAndroid(import: String): Boolean =
            import.startsWith("android.") || import.startsWith("androidx.")

        fun isAllowed(import: String): Boolean =
            import in ALLOWED_NAMES || ALLOWED_PREFIXES.any { import.startsWith(it) }
    }
}
