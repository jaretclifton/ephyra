package dev.jdtech.jellyfin.repository

import android.content.Context
import android.provider.Settings
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.player.DeviceProfileBuilder
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.Closeable
import java.io.InputStream
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.robolectric.RuntimeEnvironment

/**
 * A stand-in Jellyfin server on a [ServerSocket], for tests that run a real
 * [JellyfinRepositoryImpl] and read what it sent. It records every request and answers each with
 * [answer]: a JSON body with 200, or 204 when [answer] gives `null`. One connection per request
 * (`Connection: close`).
 */
internal class StandInServer(private val answer: (Request) -> String? = { null }) : Closeable {
    /** A request as it reached the server. [target] is the path with its query, as sent. */
    data class Request(val method: String, val target: String, val body: String) {
        val path: String
            get() = target.substringBefore('?')

        /** The target with its query decoded, for matching on query values. */
        val decodedTarget: String
            get() = URLDecoder.decode(target, "UTF-8")

        fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject
    }

    // Loopback only: the repository is the one client, on 127.0.0.1.
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    val baseUrl: String
        get() = "http://127.0.0.1:${socket.localPort}"

    init {
        // The SDK logs each request through kotlin-logging, which picks SLF4J unless told to use
        // android.util.Log. The tests have no SLF4J; Robolectric has android.util.Log.
        System.setProperty("kotlin-logging-to-android-native", "true")
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: break
                connection.use {
                    val input = it.getInputStream()
                    val requestLine = readLine(input).split(" ")
                    var length = 0
                    while (true) {
                        val header = readLine(input)
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            length = header.substringAfter(':').trim().toInt()
                        }
                    }
                    val request =
                        Request(
                            method = requestLine.getOrElse(0) { "" },
                            target = requestLine.getOrElse(1) { "" },
                            body = String(input.readNBytes(length)),
                        )
                    requests += request
                    val body = answer(request)
                    val response =
                        if (body == null) "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
                        else
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.toByteArray().size}\r\n" +
                                "Connection: close\r\n\r\n$body"
                    it.getOutputStream().write(response.toByteArray())
                }
            }
        }
    }

    /** A header line, without its line end; the body is read by length, so no buffered reader. */
    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1 || c == '\n'.code) break
            if (c != '\r'.code) line.append(c.toChar())
        }
        return line.toString()
    }

    override fun close() = socket.close()

    companion object {
        val USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

        /** The test application, with the device id the SDK names the device after. */
        fun context(): Context =
            RuntimeEnvironment.getApplication().also {
                // Robolectric has none.
                Settings.Secure.putString(it.contentResolver, Settings.Secure.ANDROID_ID, "test")
            }

        fun preferences(context: Context): AppPreferences =
            AppPreferences(context.getSharedPreferences("test", Context.MODE_PRIVATE))

        /**
         * A database with no downloads, which fails on anything but the question whether there are
         * any: with no current server set, the requests under test read nothing else.
         */
        fun noDownloads(): ServerDatabaseDao =
            Proxy.newProxyInstance(
                ServerDatabaseDao::class.java.classLoader,
                arrayOf(ServerDatabaseDao::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "observeHasSources" -> flowOf(false)
                    else -> throw NotImplementedError("ServerDatabaseDao.${method.name}")
                }
            } as ServerDatabaseDao

        /** A recorded server answer under `src/test/resources/`. */
        fun resource(path: String): String =
            checkNotNull(StandInServer::class.java.classLoader?.getResource(path)) { path }
                .readText()
    }
}

/** A real repository that talks to this server as [StandInServer.USER_ID] with token `token`. */
internal fun StandInServer.repository(
    context: Context = StandInServer.context(),
    database: ServerDatabaseDao = StandInServer.noDownloads(),
    preferences: AppPreferences = StandInServer.preferences(context),
): JellyfinRepositoryImpl {
    val api =
        JellyfinApi(context).apply {
            api.update(baseUrl = baseUrl, accessToken = "token")
            userId = StandInServer.USER_ID
        }
    return JellyfinRepositoryImpl(
        context,
        api,
        database,
        preferences,
        DeviceProfileBuilder(context),
    )
}
