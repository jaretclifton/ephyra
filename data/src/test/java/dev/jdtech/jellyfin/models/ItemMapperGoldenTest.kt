package dev.jdtech.jellyfin.models

import android.app.Application
import dev.jdtech.jellyfin.server.jellyfin.mappers.MapperContext
import dev.jdtech.jellyfin.server.jellyfin.mappers.toFindroidBoxSet
import dev.jdtech.jellyfin.server.jellyfin.mappers.toFindroidCollection
import dev.jdtech.jellyfin.server.jellyfin.mappers.toFindroidEpisode
import dev.jdtech.jellyfin.server.jellyfin.mappers.toFindroidFolder
import dev.jdtech.jellyfin.server.jellyfin.mappers.toFindroidItem
import dev.jdtech.jellyfin.server.jellyfin.mappers.toFindroidMovie
import dev.jdtech.jellyfin.server.jellyfin.mappers.toFindroidPerson
import dev.jdtech.jellyfin.server.jellyfin.mappers.toFindroidSeason
import dev.jdtech.jellyfin.server.jellyfin.mappers.toFindroidSegment
import dev.jdtech.jellyfin.server.jellyfin.mappers.toFindroidShow
import dev.jdtech.jellyfin.server.jellyfin.mappers.toFindroidSource
import java.io.File
import java.lang.reflect.Modifier
import java.util.TimeZone
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CE (#739): golden tests of the functions that turn the Jellyfin SDK's items into the app's own
 * models. Each test reads a recorded item from `src/test/resources/golden/<name>.json`, maps it
 * with a fixed base URL, prints the result and compares it with `<name>.expected.txt`.
 *
 * The JSON files are responses of the test server (`docs/dev-environment.md`), as it sent them but
 * for the access key in a delivery URL. The `*.handwritten.json` files are written by hand:
 * `boxset` because the server has no collection, `movie-edge-cases` for the branches no recorded
 * item reaches. `source*.json` are the first media source of a PlaybackInfo response.
 *
 * The expected files pin what the mappers do today, also where that looks odd. Changing a mapper on
 * purpose means regenerating them and reading the diff: `./gradlew :data:testDebugUnitTest --tests
 * "*ItemMapperGoldenTest" -Dgolden.update=true`.
 */
// Robolectric for android.net.Uri, which FindroidImages holds.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ItemMapperGoldenTest {
    private val defaultZone = TimeZone.getDefault()

    // The SDK turns the server's UTC instants ("...Z") into local date-times in the default time
    // zone, so the dates in the expected files would move with the machine that runs the test.
    @Before fun fixTimeZone() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After fun restoreTimeZone() = TimeZone.setDefault(defaultZone)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val context =
        MapperContext(BASE_URL) { itemId, mediaSourceId ->
            "$BASE_URL/Videos/$itemId/stream?mediaSourceId=$mediaSourceId"
        }

    private fun item(name: String): BaseItemDto =
        json.decodeFromString(BaseItemDto.serializer(), resource("$name.json"))

    @Test
    fun movie() {
        val mapped = runBlocking { item("movie").toFindroidMovie(context, emptyMap()) }
        golden("movie", mapped)
    }

    /**
     * The branches no recorded item reaches: duplicate and blank trailers and studios, a 3D flag, a
     * link that is no web URL, two trickplay widths, named chapters, a transcoded second source.
     */
    @Test
    fun movieEdgeCases() {
        val mapped = runBlocking {
            item("movie-edge-cases.handwritten").toFindroidMovie(context, emptyMap())
        }
        golden("movie-edge-cases", mapped)
    }

    @Test
    fun movieThroughToFindroidItem() {
        val viaItem = runBlocking { item("movie").toFindroidItem(context, emptyMap()) }
        val direct = runBlocking { item("movie").toFindroidMovie(context, emptyMap()) }
        assertEquals(direct, viaItem)
    }

    @Test
    fun episode() {
        val mapped = runBlocking { item("episode").toFindroidEpisode(context, emptyMap()) }
        golden("episode", assertNotNull(mapped))
    }

    @Test
    fun show() {
        golden("show", item("show").toFindroidShow(BASE_URL))
    }

    @Test
    fun season() {
        golden("season", item("season").toFindroidSeason(BASE_URL))
    }

    @Test
    fun boxSet() {
        golden("boxset", item("boxset.handwritten").toFindroidBoxSet(BASE_URL))
    }

    @Test
    fun collection() {
        golden("collection", assertNotNull(item("collection").toFindroidCollection(BASE_URL)))
    }

    @Test
    fun folder() {
        golden("folder", item("folder").toFindroidFolder(BASE_URL))
    }

    @Test
    fun person() {
        golden("person", item("person").toFindroidPerson(BASE_URL))
    }

    /** A home video maps as a movie (#671). */
    @Test
    fun video() {
        val mapped = runBlocking { item("video").toFindroidItem(context, emptyMap()) }
        assertEquals(FindroidMovie::class, assertNotNull(mapped)::class)
        golden("video", mapped)
    }

    @Test
    fun source() {
        val itemId = UUID.fromString("7cfc27a5-0818-7be5-210c-6df99c9d6102")
        val parts =
            listOf("source" to "direct", "source-transcode" to "transcode").flatMap { (file, title)
                ->
                val info =
                    json.decodeFromString(MediaSourceInfo.serializer(), resource("$file.json"))
                listOf(
                    "$title, includePath = false" to
                        runBlocking { info.toFindroidSource(context, itemId) },
                    "$title, includePath = true" to
                        runBlocking { info.toFindroidSource(context, itemId, includePath = true) },
                )
            }
        golden("source", *parts.toTypedArray())
    }

    @Test
    fun kindsTheAppDoesNotShow() {
        // The kind picks the mapper; a kind without one is dropped.
        val audio = item("person").copy(type = BaseItemKind.AUDIO)
        assertEquals(null, runBlocking { audio.toFindroidItem(context, emptyMap()) })
    }

    @Test
    fun segment() {
        val segment =
            MediaSegmentDto(
                id = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000b1"),
                itemId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"),
                type = MediaSegmentType.INTRO,
                startTicks = 305_000_000L,
                endTicks = 905_000_000L,
            )
        assertEquals(
            FindroidSegment(FindroidSegmentType.INTRO, startTicks = 30_500, endTicks = 90_500),
            segment.toFindroidSegment(),
        )
    }

    // -- The golden machinery ------------------------------------------------------------------

    private fun golden(name: String, vararg parts: Pair<String, Any?>) {
        val actual = parts.joinToString("\n") { (title, value) -> "== $title\n${print(value)}\n" }
        compare(name, actual)
    }

    private fun golden(name: String, value: Any?) = compare(name, print(value) + "\n")

    private fun compare(name: String, actual: String) {
        if (System.getProperty("golden.update") == "true") {
            val dir = File(System.getProperty("user.dir"), "src/test/resources/golden")
            dir.mkdirs()
            File(dir, "$name.expected.txt").writeText(actual)
            return
        }
        assertEquals(resource("$name.expected.txt"), actual, "golden/$name.expected.txt")
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("golden/$path")) {
                "missing golden/$path"
            }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
            .replace("\r\n", "\n")

    /**
     * One property per line, nested values indented. Walks the app's own models by reflection, in
     * declaration order. Dates print as ISO strings (they have no zone), floats the way Kotlin
     * does, so nothing depends on the default locale or time zone.
     */
    private fun print(value: Any?, indent: String = ""): String =
        when {
            value == null -> "null"
            value is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            value is Enum<*> -> value.name
            value is Collection<*> ->
                if (value.isEmpty()) "[]"
                else
                    value.joinToString("\n", prefix = "[\n", postfix = "\n$indent]") {
                        "$indent  - ${print(it, "$indent    ")}"
                    }
            value is Map<*, *> ->
                if (value.isEmpty()) "{}"
                else
                    value.entries.joinToString("\n", prefix = "{\n", postfix = "\n$indent}") {
                        "$indent  ${print(it.key)}: ${print(it.value, "$indent  ")}"
                    }
            value.javaClass.name.startsWith("dev.jdtech.jellyfin.models.") -> {
                val fields =
                    value.javaClass.declaredFields.filter {
                        !Modifier.isStatic(it.modifiers) && !it.isSynthetic
                    }
                fields.joinToString(
                    "\n",
                    prefix = "${value.javaClass.simpleName}(\n",
                    postfix = "\n$indent)",
                ) {
                    it.isAccessible = true
                    "$indent  ${it.name} = ${print(it.get(value), "$indent  ")}"
                }
            }
            else -> value.toString()
        }

    private companion object {
        const val BASE_URL = "https://server.test"
    }
}
