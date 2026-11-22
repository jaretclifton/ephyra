package dev.jdtech.jellyfin.server.jellyfin.contract

import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.repository.StandInServer.Request
import dev.jdtech.jellyfin.repository.StandInServer.Response
import dev.jdtech.jellyfin.testing.ContractLibrary
import dev.jdtech.jellyfin.testing.ContractLibrary.Part
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * #771: the Docker test server behind the contract tests' stand-in, when `EPHYRA_TEST_SERVER` is
 * set. It presents [ContractLibrary] from the server's own items:
 *
 * - The adapter only ever sees the library's fixed ids. Each request has them replaced by the
 *   server's, each answer the other way round. An id of the server the library does not name
 *   becomes a fake one derived from it (twelve zeros and a hash), so the recordings hold no id,
 *   token or image tag of the server, and the same server gives the same recordings.
 * - The adapter's token is `token`; the requests go out with the token of this class's own login,
 *   and a token the adapter got from a login of its own is translated like an id.
 * - Before every test the user data of the library is set as [ContractLibrary] has it.
 */
internal class LiveServer
private constructor(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
) {
    private val fakeToReal = ConcurrentHashMap<String, String>()
    private val realToFake = ConcurrentHashMap<String, String>()

    private val token: String
    private val userId: String

    /** The server's ids of the library's movies and episodes, whose user data a test may change. */
    private val userDataItems: List<String>

    val notOnServer: Set<Part>

    init {
        val login =
            json(
                call(
                    "POST",
                    "/Users/AuthenticateByName",
                    """{"Username":${quote(username)},"Pw":${quote(password)}}""",
                    token = null,
                    device = "ephyra-contract-proxy",
                )
            )
        token = login.text("AccessToken")
        userId = login.getValue("User").jsonObject.text("Id")
        link(ContractLibrary.userId.asString().n(), userId)
        link(ContractLibrary.server.id, json(get("/System/Info/Public")).text("Id"))

        val library = ContractLibrary
        library.movies.forEach { movie ->
            find("Movie", movie.name)?.let { link(movie.id.asString().n(), it) }
        }
        val series = checkNotNull(find("Series", library.series.name)) { "no Test Show" }
        link(library.series.id.asString().n(), series)
        val seasons = json(get("/Shows/$series/Seasons?userId=$userId")).items()
        for (season in library.seasons) {
            seasons
                .firstOrNull { it["IndexNumber"]?.jsonPrimitive?.int == season.indexNumber }
                ?.let { link(season.id.asString().n(), it.text("Id")) }
        }
        val episodes = json(get("/Shows/$series/Episodes?userId=$userId")).items()
        for (episode in library.episodes) {
            episodes
                .firstOrNull {
                    it["ParentIndexNumber"]?.jsonPrimitive?.int == episode.parentIndexNumber &&
                        it["IndexNumber"]?.jsonPrimitive?.int == episode.indexNumber
                }
                ?.let { link(episode.id.asString().n(), it.text("Id")) }
        }
        val person =
            json(get("/Persons?userId=$userId&searchTerm=${encode(library.person.name)}"))
                .items()
                .firstOrNull { it.text("Name") == library.person.name }
        person?.let { link(library.person.id.asString().n(), it.text("Id")) }
        val boxSet = find("BoxSet", library.boxSet.name)
        boxSet?.let { link(library.boxSet.id.asString().n(), it) }
        val segments =
            fakeToReal[library.episodeWithSegments.id.asString().n()]?.let {
                json(get("/MediaSegments/$it")).items()
            }
        val realSeries = fakeToReal.getValue(library.series.id.asString().n())
        val themeSongs = json(get("/Items/$realSeries/ThemeSongs?userId=$userId")).items()
        val movie = fakeToReal[library.movieWithResume.id.asString().n()]
        val similar = movie?.let { id ->
            json(get("/Items/$id/Similar?userId=$userId&limit=12")).items().filterNot {
                it.text("Id") == id
            }
        }

        userDataItems =
            (library.movies + library.episodes).mapNotNull { fakeToReal[it.id.asString().n()] }
        check(userDataItems.size == library.movies.size + library.episodes.size) {
            "the test server does not have every item of ContractLibrary"
        }
        notOnServer = buildSet {
            if (boxSet == null) add(Part.BOX_SET)
            if (person == null) add(Part.PERSON)
            if (segments.isNullOrEmpty()) add(Part.SEGMENTS)
            if (themeSongs.isEmpty()) add(Part.THEME_SONG)
            if (similar.isNullOrEmpty()) add(Part.SIMILAR_ITEMS)
        }
    }

    private val secretFakeToReal = ConcurrentHashMap<String, String>()
    private val secretRealToFake = ConcurrentHashMap<String, String>()

    /** The server's Quick Connect secrets (64 hex digits) made up, as ids are. */
    private fun fakeSecrets(text: String): String =
        SECRET.replace(text) { match ->
            secretRealToFake.getOrPut(match.value.lowercase()) {
                val hash = MessageDigest.getInstance("SHA-256").digest(match.value.toByteArray())
                val fake =
                    FAKE_PREFIX +
                        hash.joinToString("") { "%02x".format(it) }.take(64 - FAKE_PREFIX.length)
                secretFakeToReal[fake] = match.value
                fake
            }
        }

    private fun realSecrets(text: String): String =
        SECRET.replace(text) { secretFakeToReal[it.value.lowercase()] ?: it.value }

    /** Sets the user data of the library on the server as [ContractLibrary] has it. */
    fun resetUserData() {
        val user = "?userId=$userId"
        for (id in userDataItems) {
            call("DELETE", "/UserFavoriteItems/$id$user")
            call("DELETE", "/UserPlayedItems/$id$user")
        }
        for (item in ContractLibrary.items) {
            val id = fakeToReal[item.id.asString().n()] ?: continue
            if (item.favorite) call("POST", "/UserFavoriteItems/$id$user")
            if (item.played) call("POST", "/UserPlayedItems/$id$user")
            if (item.playbackPositionTicks > 0) {
                call(
                    "POST",
                    "/UserItems/$id/UserData$user",
                    """{"PlaybackPositionTicks":${item.playbackPositionTicks}}""",
                )
            }
        }
    }

    /** [request] passed on to the server, and its answer, both translated. */
    fun forward(request: Request): Response {
        val sent = request.headers["authorization"]?.let { TOKEN.find(it)?.groupValues?.get(1) }
        val realToken =
            when (sent) {
                null -> null
                FAKE_TOKEN -> token
                else -> fakeToReal[sent] ?: sent
            }
        val connection =
            open(
                request.method,
                toReal(request.target),
                toReal(request.body).takeIf { request.body.isNotEmpty() },
                realToken,
                // A login of the adapter must not end this class's session.
                if (realToken == null) "ephyra-contract-login" else "ephyra-contract-proxy",
            )
        val status = connection.responseCode
        val contentType = connection.contentType ?: "application/json"
        val bytes =
            (if (status >= 400) connection.errorStream else connection.inputStream)?.use {
                it.readBytes()
            } ?: ByteArray(0)
        val body =
            when {
                // The adapter only passes an image on: a short one is enough.
                contentType.startsWith("image/") -> bytes.copyOf(minOf(bytes.size, 64))
                else -> toFake(bytes.toString(Charsets.UTF_8)).toByteArray()
            }
        return Response(status, contentType, body)
    }

    private fun link(fake: String, real: String) {
        fakeToReal[fake.lowercase()] = real.lowercase().replace("-", "")
        realToFake[real.lowercase().replace("-", "")] = fake.lowercase()
    }

    private fun toReal(text: String): String =
        ID.replace(realSecrets(text)) { match ->
            val n = match.value.lowercase().replace("-", "")
            val real = fakeToReal[n] ?: return@replace match.value
            if ('-' in match.value) dashed(real) else real
        }

    private fun toFake(text: String): String {
        val plain =
            text
                .replace(token, FAKE_TOKEN)
                .replace(baseUrl.trimEnd('/'), SERVER)
                .replace(LOCAL_ADDRESS, "\"LocalAddress\":\"$SERVER\"")
                // The address the server saw this test come from.
                .replace(REMOTE_END_POINT, "\"RemoteEndPoint\":\"127.0.0.1\"")
        return ID.replace(fakeSecrets(plain)) { match ->
            val n = match.value.lowercase().replace("-", "")
            val fake = realToFake[n] ?: derived(n).also { link(it, n) }
            if ('-' in match.value) dashed(fake) else fake
        }
    }

    /** A fake id for the server's [real] one: twelve zeros, then a hash of it. */
    private fun derived(real: String): String {
        if (real.startsWith(FAKE_PREFIX)) return real
        val hash = MessageDigest.getInstance("SHA-256").digest(real.toByteArray())
        return FAKE_PREFIX +
            hash.joinToString("") { "%02x".format(it) }.take(32 - FAKE_PREFIX.length)
    }

    private fun find(kind: String, name: String): String? {
        val term = encode(name.substringBefore(" ("))
        return json(
                get("/Items?userId=$userId&recursive=true&includeItemTypes=$kind&searchTerm=$term")
            )
            .items()
            .firstOrNull { it.text("Name") == name }
            ?.text("Id")
    }

    private fun get(path: String): String = call("GET", path)

    private fun call(
        method: String,
        path: String,
        body: String? = null,
        token: String? = this.token,
        device: String = "ephyra-contract-proxy",
    ): String {
        val connection = open(method, path, body, token, device)
        val status = connection.responseCode
        check(status < 400) { "$method ${path.substringBefore('?')}: HTTP $status" }
        return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun open(
        method: String,
        target: String,
        body: String?,
        token: String?,
        device: String,
    ): HttpURLConnection {
        val connection =
            URI(baseUrl.trimEnd('/') + target).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty(
            "Authorization",
            "MediaBrowser Client=\"Ephyra contract tests\", Device=\"contract\", " +
                "DeviceId=\"$device\", Version=\"1\"" +
                (token?.let { ", Token=\"$it\"" } ?: ""),
        )
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        return connection
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.items() = getValue("Items").jsonArray.map { it.jsonObject }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    companion object {
        /** The test server, or null when the tests run from their recordings. */
        val instance: LiveServer? by lazy {
            val url = System.getenv("EPHYRA_TEST_SERVER")?.takeIf { it.isNotBlank() }
            url?.let {
                LiveServer(
                    it,
                    System.getenv("EPHYRA_TEST_USER")?.takeIf(String::isNotBlank)
                        ?: ContractLibrary.USER_NAME,
                    System.getenv("EPHYRA_TEST_PASSWORD") ?: ContractLibrary.PASSWORD,
                )
            }
        }

        /** What the recordings name the server's address. */
        const val SERVER = "http://server.invalid"

        const val FAKE_PREFIX = "000000000000"

        /** The token the adapter is given. */
        const val FAKE_TOKEN = "token"

        /**
         * An id, a token or an image tag: 32 hex digits alone, or a UUID with its dashes, which may
         * have more after it (the user data key of an episode is the series id and numbers). A
         * longer run of hex digits, such as a Quick Connect secret, is none.
         */
        val ID =
            Regex(
                "(?<![0-9a-fA-F-])(?:[0-9a-fA-F]{32}(?![0-9a-fA-F-])|[0-9a-fA-F]{8}-" +
                    "[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
            )

        /** A Quick Connect secret: 64 hex digits. */
        val SECRET = Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])")

        private val TOKEN = Regex("Token=\"([^\"]*)\"")

        private val LOCAL_ADDRESS = Regex("\"LocalAddress\":\"[^\"]*\"")

        private val REMOTE_END_POINT = Regex("\"RemoteEndPoint\":\"[^\"]*\"")

        private fun String.n(): String = replace("-", "")

        private fun dashed(n: String) =
            "${n.substring(0, 8)}-${n.substring(8, 12)}-${n.substring(12, 16)}-" +
                "${n.substring(16, 20)}-${n.substring(20)}"

        private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

        private fun quote(text: String) = Json.encodeToString(String.serializer(), text)
    }
}
