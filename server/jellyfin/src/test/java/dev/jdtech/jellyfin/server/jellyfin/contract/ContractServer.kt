package dev.jdtech.jellyfin.server.jellyfin.contract

import dev.jdtech.jellyfin.repository.StandInServer
import dev.jdtech.jellyfin.repository.StandInServer.Request
import dev.jdtech.jellyfin.repository.StandInServer.Response
import dev.jdtech.jellyfin.testing.ContractLibrary
import dev.jdtech.jellyfin.testing.ContractLibrary.Part
import java.io.File
import java.util.Base64
import java.util.Collections
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * #771: the server the Jellyfin adapter's contract tests talk to, a [StandInServer] for each test.
 *
 * - By default it answers with the recordings of the test under `src/test/resources/contract/<test
 *   class>/<test>.json`, matched by method and target in the order they were made; a request
 *   without a recording fails the test.
 * - With the environment variable `EPHYRA_TEST_SERVER` (the base URL of the Docker test server,
 *   `docs/dev-environment.md`) it passes every request on to that server as `EPHYRA_TEST_USER`
 *   (default `contract`, password `EPHYRA_TEST_PASSWORD`, default empty), after it set the user
 *   data of [ContractLibrary] on it. See [LiveServer] for how ids and tokens are translated.
 * - With `-Dcontract.record=true` as well, it writes what the live server answered as the test's
 *   recordings. Hand-written recordings (marked `"handwritten": true`) are kept.
 */
class ContractServer : TestRule {
    private lateinit var standIn: StandInServer
    private val missing: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val exchanges: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    private var recordings: List<JsonObject> = emptyList()
    private val used = mutableSetOf<Int>()

    val baseUrl: String
        get() = standIn.baseUrl

    /** The parts of [ContractLibrary] the server holds no data for. */
    val notOnServer: Set<Part>
        get() = live?.notOnServer ?: emptySet()

    private val live: LiveServer?
        get() = LiveServer.instance

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val file = "${description.testClass.simpleName}/${fileName(description)}.json"
                recordings = if (live == null) readRecordings(file) else emptyList()
                live?.resetUserData()
                standIn = StandInServer(concurrent = true, respond = ::respond)
                try {
                    base.evaluate()
                } catch (e: Throwable) {
                    if (missing.isNotEmpty()) throw AssertionError(missingMessage(file), e)
                    throw e
                } finally {
                    standIn.close()
                }
                if (missing.isNotEmpty()) throw AssertionError(missingMessage(file))
                if (live != null && RECORD) writeRecordings(file)
            }
        }

    private fun missingMessage(file: String) =
        "No recording in contract/$file for:\n${missing.joinToString("\n")}\n" +
            "Record it with EPHYRA_TEST_SERVER=http://localhost:8096 and -Dcontract.record=true."

    private fun respond(request: Request): Response {
        // The SDK's discovery also tries https on the same port: not HTTP, so no request.
        if (!request.method.matches(Regex("[A-Z]+")) || !request.target.startsWith("/")) {
            return Response(400)
        }
        val key = key(request)
        val server = live
        if (server != null) {
            val response = server.forward(request)
            exchanges += exchange(key, response)
            return response
        }
        synchronized(used) {
            val matching = recordings.indices.filter { recordings[it].text("request") == key }
            // The first unused one, else the last: a request may be repeated (a retry).
            val index = matching.firstOrNull { it !in used } ?: matching.lastOrNull()
            if (index == null) {
                missing += key
                return Response(500, body = "no recording".toByteArray())
            }
            used += index
            return response(recordings[index])
        }
    }

    private fun readRecordings(file: String): List<JsonObject> {
        val text =
            ContractServer::class.java.classLoader?.getResource("contract/$file")?.readText()
                ?: return emptyList()
        return Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
    }

    private fun writeRecordings(file: String) {
        val target = File(RESOURCES, file)
        val handwritten =
            if (target.exists()) {
                Json.parseToJsonElement(target.readText())
                    .jsonArray
                    .map { it.jsonObject }
                    .filter { it["handwritten"]?.jsonPrimitive?.content == "true" }
            } else emptyList()
        val all = synchronized(exchanges) { exchanges.toList() } + handwritten
        if (all.isEmpty()) {
            target.delete()
            return
        }
        target.parentFile.mkdirs()
        target.writeText(PRETTY.encodeToString(JsonArray.serializer(), JsonArray(all)) + "\n")
    }

    private fun exchange(key: String, response: Response): JsonObject = buildJsonObject {
        put("request", key)
        put("status", response.status)
        if (response.body.isEmpty()) return@buildJsonObject
        put("contentType", response.contentType)
        val text = response.body.toString(Charsets.UTF_8)
        val json =
            if (response.contentType.contains("json")) {
                runCatching { Json.parseToJsonElement(text) }.getOrNull()
            } else null
        when {
            json != null -> put("json", json)
            response.contentType.startsWith("text/") -> put("text", text)
            else -> put("base64", Base64.getEncoder().encodeToString(response.body))
        }
    }

    private fun response(recording: JsonObject): Response {
        val status = recording.getValue("status").jsonPrimitive.int
        val contentType = recording.text("contentType") ?: "application/json"
        val body =
            recording["json"]
                ?.let { Json.encodeToString(JsonElement.serializer(), it) }
                ?.toByteArray()
                ?: recording.text("text")?.toByteArray()
                ?: recording.text("base64")?.let { Base64.getDecoder().decode(it) }
                ?: ByteArray(0)
        return Response(status, contentType, body)
    }

    private fun JsonObject.text(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        /** `-Dcontract.record=true`: write the live server's answers as the recordings. */
        private val RECORD = System.getProperty("contract.record") == "true"

        /** Where the recordings are written: set by the build to `src/test/resources/contract`. */
        private val RESOURCES = File(System.getProperty("contract.resources").orEmpty())

        private val PRETTY = Json { prettyPrint = true }

        /**
         * A request as the recordings name it: its method and target, without an API key, and for a
         * request whose body carries a decision, the fields that carry it ([DECISIONS]). So an
         * adapter that asks differently (another cap, a lone audio index, another position) finds
         * no recording, as a changed query does. The device profile is left out: it follows the
         * device, not the request.
         */
        internal fun key(request: Request): String {
            val path = request.target.substringBefore('?')
            val query =
                request.target.substringAfter('?', "").split('&').filter {
                    it.isNotEmpty() &&
                        !it.startsWith("api_key=", ignoreCase = true) &&
                        !it.startsWith("ApiKey=", ignoreCase = true)
                }
            val target =
                "${request.method} $path" +
                    if (query.isEmpty()) "" else "?" + query.joinToString("&")
            val fields =
                DECISIONS.entries.firstOrNull { it.key.matches(path) }?.value ?: return target
            val body =
                runCatching { Json.parseToJsonElement(request.body).jsonObject }.getOrNull()
                    ?: return "$target {}"
            val decision =
                JsonObject(fields.filter { it in body }.associateWith { body.getValue(it) })
            return "$target $decision"
        }

        /** The body fields that carry the decision of a request, by the paths they are sent to. */
        private val DECISIONS =
            mapOf(
                Regex("/Items/[^/]+/PlaybackInfo") to
                    listOf(
                        "UserId",
                        "MaxStreamingBitrate",
                        "EnableTranscoding",
                        "EnableDirectPlay",
                        "EnableDirectStream",
                        "AllowVideoStreamCopy",
                        "AllowAudioStreamCopy",
                        "AutoOpenLiveStream",
                        "MediaSourceId",
                        "AudioStreamIndex",
                    ),
                Regex("/Sessions/Playing(/Progress|/Stopped)?") to
                    listOf("ItemId", "PositionTicks", "PlaySessionId", "IsPaused", "PlayMethod"),
                // Not the password: the order of the login tests tells the answers apart.
                Regex("/Users/AuthenticateByName") to listOf("Username"),
            )

        private fun fileName(description: Description): String =
            description.methodName.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
    }
}
