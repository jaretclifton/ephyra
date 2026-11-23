package dev.jdtech.jellyfin.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R16 (#899). The server in use, its type, address and token and the user, is one value
 * (`ActiveServer`) on one holder (`ActiveServerHolder`, in `domain`), written as a whole. Before,
 * the type was set on one field and then the address and token on the client, and a request in
 * between got one server's adapter with the other's address and token. In production code:
 * 1. only `ActiveServer.kt` keeps a mutable `ActiveServer` (a `MutableStateFlow`, an
 *    `AtomicReference` or a `var` of that type): no second copy that could lag behind;
 * 2. a server is put on (`.switchTo(`) only by the session restore (`ApiSession.kt`) and by the
 *    setup when it adds or switches to a server (`SetupRepositoryImpl.kt`);
 * 3. no `copy(type = …)` next to an `ActiveServer` (but in the holder, whose copy keeps the type):
 *    a new type comes with a whole new value, never on the old address and token;
 * 4. nothing writes the address, the token or the user past the holder: no call of the SDK client's
 *    `update(…)` and, in the Jellyfin adapter, no assignment to a `userId` and no `session.use(`
 *    (both kept for the adapter's tests only).
 *
 * No baseline: it starts at zero. Tests are not production files.
 */
class ActiveServerTest {

    @Test
    fun `R16 the server in use is written as one value, in one place`() {
        val files = ProjectTree.productionKotlinFiles
        assertTrue(files.size >= MIN_FILES, "Only ${files.size} production Kotlin files found")
        assertTrue(
            HOLDER_FILE in files,
            "$HOLDER_FILE not found, so the rule looks at the wrong file",
        )
        SWITCH_FILES.forEach { assertTrue(it in files, "$it not found") }

        val code = files.mapValues { (_, file) -> code(file.readText()) }
        val violations = buildList {
            code
                .filterKeys { it != HOLDER_FILE }
                .filterValues { MUTABLE_COPY.containsMatchIn(it) }
                .keys
                .forEach { add("$it: keeps an ActiveServer of its own") }
            code
                .filterKeys { it != HOLDER_FILE && it !in SWITCH_FILES }
                .filterValues { SWITCH.containsMatchIn(it) }
                .keys
                .forEach { add("$it: puts a server on (switchTo)") }
            code
                // The holder's own copy keeps the type as it is (onSameServer).
                .filterKeys { it != HOLDER_FILE }
                .filterValues { "ActiveServer" in it && TYPE_COPY.containsMatchIn(it) }
                .keys
                .forEach { add("$it: changes the type of an ActiveServer with copy") }
            code
                .filterValues { SDK_UPDATE.containsMatchIn(it) }
                .keys
                .forEach { add("$it: calls the SDK client's update") }
            code
                .filterKeys { ProjectTree.inJellyfinAdapter(it) }
                .filterValues { ADAPTER_WRITE.containsMatchIn(it) }
                .keys
                .forEach { add("$it: sets the user or uses a session past the holder") }
        }
        assertTrue(
            violations.isEmpty(),
            "Write the server in use as one ActiveServer through ActiveServerHolder " +
                "(domain, ports/ActiveServer.kt):\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun `R16 finds each kind of write, and nothing else`() {
        fun finds(regex: Regex, source: String) = regex.containsMatchIn(code(source))

        assertTrue(finds(MUTABLE_COPY, "val s = MutableStateFlow<ActiveServer>(x)"))
        assertTrue(finds(MUTABLE_COPY, "private val r = AtomicReference<ActiveServer>()"))
        assertTrue(finds(MUTABLE_COPY, "@Volatile var current: ActiveServer? = null"))
        assertEquals(false, finds(MUTABLE_COPY, "val current: ActiveServer = holder.value"))
        assertEquals(false, finds(MUTABLE_COPY, "// var current: ActiveServer"))

        assertTrue(finds(SWITCH, "active.switchTo(ActiveServer(type = t))"))
        assertEquals(false, finds(SWITCH, "private suspend fun switchTo(userId: UserId)"))
        assertEquals(false, finds(SWITCH, "    switchTo(userId)"))

        assertTrue(finds(TYPE_COPY, "active.switchTo(active.value.copy(type = \"emby\"))"))
        assertTrue(finds(TYPE_COPY, "it.copy( type= t, baseUrl = b)"))
        assertEquals(false, finds(TYPE_COPY, "it.copy(accessToken = null, userId = null)"))

        assertTrue(finds(SDK_UPDATE, "api.update(baseUrl = url, accessToken = token)"))
        assertTrue(finds(SDK_UPDATE, "jellyfinApi.api.update(accessToken = null)"))
        assertEquals(false, finds(SDK_UPDATE, "state.update { it + 1 }"))
        assertEquals(false, finds(SDK_UPDATE, "serverStore.updateServer(server)"))

        assertTrue(finds(ADAPTER_WRITE, "api.userId = id.toUuid()"))
        assertTrue(finds(ADAPTER_WRITE, "session.use(baseUrl, token, user)"))
        assertEquals(false, finds(ADAPTER_WRITE, "userId = api.currentUserId,"))
        assertEquals(false, finds(ADAPTER_WRITE, "if (api.userId == id) return"))
        assertEquals(false, finds(ADAPTER_WRITE, "response.use { it.code }"))
    }

    private companion object {
        /** About 900 today; far fewer means the tree was not found. */
        const val MIN_FILES = 600

        const val HOLDER_FILE =
            "domain/src/main/kotlin/dev/jdtech/jellyfin/domain/ports/ActiveServer.kt"

        val SWITCH_FILES =
            setOf(
                "core/src/main/java/dev/jdtech/jellyfin/di/ApiSession.kt",
                "setup/src/main/java/dev/jdtech/jellyfin/setup/data/SetupRepositoryImpl.kt",
            )

        val MUTABLE_COPY =
            Regex(
                """(MutableStateFlow|AtomicReference)\s*<\s*ActiveServer\b|\bvar\s+\w+\s*:\s*ActiveServer\b"""
            )
        val SWITCH = Regex("""\.switchTo\(""")
        val TYPE_COPY = Regex("""\.copy\(\s*type\s*=""")
        val SDK_UPDATE = Regex("""\bapi\.update\(""")
        val ADAPTER_WRITE = Regex("""\.userId\s*=(?!=)|\bsession\.use\(""")

        fun code(source: String) = UuidBoundaryTest.withoutComments(source)
    }
}
