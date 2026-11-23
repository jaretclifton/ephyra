package dev.jdtech.jellyfin.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R11 (#774). The app's own types carry the app's own names: no production class, interface, object
 * or typealias is called `Findroid…` (the models were `FindroidItem`, `FindroidMovie`, …
 * before #774; only the UI theme `FindroidTheme` keeps the old name on purpose), and no type is
 * called `Jellyfin…` outside the Jellyfin adapter, where the name says what the class is an adapter
 * for. No baseline: it starts at zero.
 */
class NamesTest {

    @Test
    fun `R11 no production type is named Findroid, and none is named Jellyfin outside the adapter`() {
        val files = ProjectTree.productionKotlinFiles
        assertTrue(files.size >= MIN_FILES, "Only ${files.size} production Kotlin files found")
        assertTrue(
            files.any { (path, file) ->
                ProjectTree.inJellyfinAdapter(path) &&
                    declaredNames(file.readText()).any { it.startsWith("Jellyfin") }
            },
            "No type of the adapter is named Jellyfin…, so the check looks for the wrong text",
        )

        val violations = files.flatMap { (path, file) ->
            declaredNames(file.readText()).mapNotNull { name ->
                when {
                    isForbiddenFindroid(name) ->
                        "$path declares $name: the models and the repository dropped the " +
                            "Findroid prefix in #774 (Item, Movie, MediaRepository, …). Name " +
                            "the type after what it is."
                    name.startsWith("Jellyfin") && !ProjectTree.inJellyfinAdapter(path) ->
                        "$path declares $name outside ${ProjectTree.JELLYFIN_ADAPTER_MODULE}: " +
                            "only the Jellyfin adapter names a type after Jellyfin."
                    else -> null
                }
            }
        }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    /** The rule reads every way a type is declared, and only declarations. */
    @Test
    fun `R11 finds declarations and nothing else`() {
        val source =
            """
            package a

            /** Was a FindroidItem. */
            // class FindroidComment
            data class FindroidMovie(val id: Int)
            internal sealed interface FindroidSource
            object FindroidTheme
            enum class JellyfinThing { A }
            private typealias FindroidAlias = String
            fun toFindroidItem() = FindroidItem::class
            val x = "class FindroidString"
            /*
            class FindroidInBlock
            */
            class Holder {
                companion object FindroidFactory
            }
            companion object FindroidFactory2 {}
            """
                .trimIndent()
        assertEquals(
            listOf(
                "FindroidMovie",
                "FindroidSource",
                "FindroidTheme",
                "JellyfinThing",
                "FindroidAlias",
                "Holder",
                "FindroidFactory",
                "FindroidFactory2",
            ),
            declaredNames(source),
        )
        assertTrue(isForbiddenFindroid("FindroidMovie"))
        assertTrue(!isForbiddenFindroid("FindroidTheme"))
        assertTrue(!isForbiddenFindroid("Item"))
    }

    private companion object {
        /** About 900 today; far fewer means the tree was not found. */
        const val MIN_FILES = 600

        /** The UI theme keeps its name on purpose (CLAUDE.md). */
        val ALLOWED_FINDROID = setOf("FindroidTheme")

        val DECLARATION =
            Regex(
                """^[ \t]*(?:(?:public|private|internal|protected|data|sealed|enum|abstract|open|inner|value|annotation|fun|expect|actual|companion)\s+)*(?:class|interface|object|typealias)\s+(\w+)""",
                RegexOption.MULTILINE,
            )

        fun isForbiddenFindroid(name: String) =
            name.startsWith("Findroid") && name !in ALLOWED_FINDROID

        fun declaredNames(source: String): List<String> =
            DECLARATION.findAll(UuidBoundaryTest.withoutComments(source))
                .map { it.groupValues[1] }
                .toList()
    }
}
