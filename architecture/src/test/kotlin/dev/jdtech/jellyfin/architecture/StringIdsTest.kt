package dev.jdtech.jellyfin.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R10, second half (#902). A server id is a `ServerId` and a media source id a `MediaSourceId`
 * (`domain/.../models/Ids.kt`), not the `String` they wrap. The way back is quiet: a parameter or
 * property `serverId: String` compiles, and code then hands the id on as text where the type would
 * have stopped a mix-up with a user id or an address. So no production file outside the Jellyfin
 * adapter (`server/jellyfin`, which maps the SDK's strings) declares one of these names with a
 * `String` type.
 *
 * `architecture/baseline/string-ids.txt` is an allowlist: the serialized forms that carry the id as
 * text by design (navigation routes, the Cast receiver's custom data), each with its reason. A
 * text check, as R1 and R10; comments do not count.
 */
class StringIdsTest {

    @Test
    fun `R10 no production signature outside the adapter types a server or source id as String`() {
        val files =
            ProjectTree.productionKotlinFiles.filterKeys { !ProjectTree.inJellyfinAdapter(it) }
        assertTrue(
            files.size >= UuidBoundaryTest.MIN_FILES,
            "Only ${files.size} production Kotlin files found",
        )
        assertTrue(
            ProjectTree.productionKotlinFiles.any { (path, file) ->
                !ProjectTree.inJellyfinAdapter(path) &&
                    TYPED.containsMatchIn(UuidBoundaryTest.withoutComments(file.readText()))
            },
            "No file declares a ServerId or MediaSourceId by these names, so the check looks for " +
                "the wrong text",
        )

        val baseline = Baseline("string-ids.txt")
        baseline.assertExactly(
            files.filterValues { typesIdAsString(it.readText()) }.keys,
            unlisted = {
                "$it declares a server or media source id as a String outside the Jellyfin " +
                    "adapter (${ProjectTree.JELLYFIN_ADAPTER_MODULE}). Use ServerId or " +
                    "MediaSourceId and their helpers (toServerId(), toMediaSourceId(), " +
                    "asString(), see Ids.kt). Only a serialized form of the kind " +
                    "${baseline.path} gives a reason for belongs in that list."
            },
            stale = {
                "$it is in the allowlist but no longer types a server or source id as a String: " +
                    "delete its line from ${baseline.path}."
            },
        )
    }

    /** The rule reads what it is meant to. */
    @Test
    fun `R10 finds a server or source id typed as String`() {
        val typed =
            listOf(
                "fun f(serverId: String) = 1\n",
                "class A(val mediaSourceId: String?)\n",
                "private var currentServerId: String = \"\"\n",
                "val sourceId : String = x\n",
                "fun g(sourceIds: List<String>) = 1\n",
                "val f: (serverId: String) -> Unit = {}\n",
            )
        val notTyped =
            listOf(
                "fun f(serverId: ServerId) = 1\n",
                "class A(val mediaSourceId: MediaSourceId?)\n",
                "// fun f(serverId: String)\n",
                "/** [serverId]: String form */ val x = 1\n",
                "fun f(serverIdText: String) = 1\n",
                "val s = \"serverId\"\n",
            )

        assertEquals(typed.map { true }, typed.map(::typesIdAsString))
        assertEquals(notTyped.map { false }, notTyped.map(::typesIdAsString))
    }

    internal companion object {
        private const val NAMES = """(?:serverId|currentServerId|mediaSourceId|sourceId)s?"""

        /** A declaration of one of the names with a `String` type, or a collection of them. */
        val AS_STRING =
            Regex("""\b$NAMES\s*:\s*(?:String\b|(?:List|Set|Collection)<String>)""")

        /** The same names declared with the domain's types: what the files should say. */
        val TYPED = Regex("""\b$NAMES\s*:\s*(?:ServerId|MediaSourceId)\b""")

        fun typesIdAsString(source: String): Boolean =
            AS_STRING.containsMatchIn(UuidBoundaryTest.withoutComments(source))
    }
}
