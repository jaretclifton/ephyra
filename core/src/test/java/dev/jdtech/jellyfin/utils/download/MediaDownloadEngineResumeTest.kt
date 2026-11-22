package dev.jdtech.jellyfin.utils.download

import dev.jdtech.jellyfin.utils.NetworkConnectivity
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * CE (#850): a resumed download sends the validator of the file its partial was written from as
 * `If-Range`. When the file on the server changed, the download starts over instead of appending
 * the new file's bytes to the old ones; an unchanged file still resumes with 206.
 */
class MediaDownloadEngineResumeTest {
    private val dir: File = Files.createTempDirectory("engine-resume").toFile()
    private val dest = File(dir, "item.mkv.download")

    /** Kept across the two engines of a test, as the preferences are across a restart. */
    private val store = InMemoryResumeValidatorStore()
    private val server = StandInServer()

    @AfterEach
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `an unchanged file resumes with 206 and the bytes are right`() {
        interruptedDownload()

        finish()

        assertEquals("bytes=$CUT-", server.requests.last().range)
        assertEquals(LAST_MODIFIED, server.requests.last().ifRange)
        assertEquals(206, server.codes.last())
        assertContentEquals(OLD, dest.readBytes())
    }

    @Test
    fun `a changed file starts over and the result is the new file`() {
        interruptedDownload()
        server.replace(NEW, lastModified = "Tue, 17 Nov 2026 08:00:00 GMT")

        finish()

        assertEquals(200, server.codes.last())
        assertContentEquals(NEW, dest.readBytes())
    }

    @Test
    fun `a changed file behind a proxy that ignores If-Range starts over by its validator`() {
        interruptedDownload()
        server.honoursIfRange = false
        server.replace(NEW, lastModified = "Tue, 17 Nov 2026 08:00:00 GMT")

        finish()

        // The 206 from the new file is thrown away; the next request has no Range.
        assertEquals(listOf(206, 200), server.codes.takeLast(2))
        assertNull(server.requests.last().range)
        assertContentEquals(NEW, dest.readBytes())
    }

    @Test
    fun `a changed file with no validator at all starts over by its length`() {
        server.lastModified = null
        interruptedDownload()
        server.honoursIfRange = false
        server.replace(NEW_LONGER, lastModified = null)

        finish()

        assertContentEquals(NEW_LONGER, dest.readBytes())
    }

    @Test
    fun `a partial with no validator known resumes as before`() {
        // A partial from a build before #850: nothing is stored for it. It resumes with a plain
        // Range, and from then on its validator is known.
        dest.writeBytes(OLD.copyOf(CUT))

        finish()

        assertEquals("bytes=$CUT-", server.requests.last().range)
        assertNull(server.requests.last().ifRange)
        assertEquals(206, server.codes.last())
        assertContentEquals(OLD, dest.readBytes())
        assertEquals(ResumeValidator(LAST_MODIFIED, OLD.size.toLong()), store.get(dest))
    }

    @Test
    fun `a 416 on a complete file still finishes the download`() {
        // A finished download whose finalize failed (#848): Retry resumes it at its full length.
        engine().run()
        val done = dest.readBytes()

        finish()

        assertEquals("bytes=${OLD.size}-", server.requests.last().range)
        assertEquals(LAST_MODIFIED, server.requests.last().ifRange)
        assertEquals(416, server.codes.last())
        assertContentEquals(done, dest.readBytes())
    }

    @Test
    fun `a 416 for a file that changed under a proxy starts over`() {
        engine().run()
        server.honoursIfRange = false
        server.replace(NEW_SHORTER, lastModified = null)

        finish()

        assertEquals(listOf(416, 200), server.codes.takeLast(2))
        assertContentEquals(NEW_SHORTER, dest.readBytes())
    }

    @Test
    fun `a removed download forgets its validator`() {
        interruptedDownload()
        val engine = engine()
        engine.run()

        engine.cancel(ID)

        assertNull(store.get(dest))
    }

    /** A first attempt that loses the connection after [CUT] bytes, then the app is killed. */
    private fun interruptedDownload() {
        server.cutAfter = CUT
        val status = engine().run()
        assertEquals(DownloadStatus.FAILED, status)
        assertEquals(CUT.toLong(), dest.length())
        server.cutAfter = null
    }

    /** The next start: a new engine, with what the store kept. */
    private fun finish() {
        assertEquals(DownloadStatus.SUCCESSFUL, engine().run())
    }

    private fun engine() = MediaDownloadEngine(server.client(), Online, store)

    private fun MediaDownloadEngine.run(): Int {
        start(
            MediaDownloadEngine.Request(
                id = ID,
                url = "http://server.test/Items/item/Download",
                destFile = dest,
                allowMetered = true,
                allowRoaming = true,
            )
        )
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            val status = snapshot(ID)?.status
            if (status == DownloadStatus.SUCCESSFUL || status == DownloadStatus.FAILED) {
                return status
            }
            Thread.sleep(10)
        }
        fail("The download never ended: ${snapshot(ID)}")
    }

    /**
     * Serves one file with Range and If-Range as Jellyfin does (Last-Modified, no ETag): If-Range
     * that does not match gives 200 with the whole file, a range past the end 416.
     */
    private class StandInServer {
        class Seen(val range: String?, val ifRange: String?)

        @Volatile var content: ByteArray = OLD
        @Volatile var lastModified: String? = LAST_MODIFIED
        @Volatile var honoursIfRange = true
        /** Loses the connection after this many bytes of the body. */
        @Volatile var cutAfter: Int? = null
        val requests = CopyOnWriteArrayList<Seen>()
        val codes = CopyOnWriteArrayList<Int>()

        fun replace(bytes: ByteArray, lastModified: String?) {
            content = bytes
            this.lastModified = lastModified
        }

        fun client(): OkHttpClient =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val range = request.header("Range")
                    val ifRange = request.header("If-Range")
                    requests.add(Seen(range, ifRange))
                    val file = content
                    val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
                    val rangeApplies =
                        from != null &&
                            (!honoursIfRange || ifRange == null || ifRange == lastModified)
                    val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    lastModified?.let { builder.header("Last-Modified", it) }
                    val code =
                        when {
                            !rangeApplies -> 200
                            from!! >= file.size -> 416
                            else -> 206
                        }
                    codes.add(code)
                    when (code) {
                        200 -> builder.message("OK").body(body(file))
                        206 ->
                            builder
                                .message("Partial Content")
                                .header(
                                    "Content-Range",
                                    "bytes $from-${file.size - 1}/${file.size}",
                                )
                                .body(body(file.copyOfRange(from!!, file.size)))
                        else ->
                            builder
                                .message("Range Not Satisfiable")
                                .header("Content-Range", "bytes */${file.size}")
                                .body("".toResponseBody())
                    }
                    builder.code(code).build()
                }
                .build()

        private fun body(bytes: ByteArray): ResponseBody {
            val cut = cutAfter ?: return bytes.toResponseBody()
            return object : ResponseBody() {
                override fun contentType(): MediaType? = null

                override fun contentLength() = bytes.size.toLong()

                override fun source(): BufferedSource {
                    val sent = Buffer().write(bytes, 0, cut)
                    return object : ForwardingSource(sent) {
                            override fun read(sink: Buffer, byteCount: Long): Long {
                                val n = super.read(sink, byteCount)
                                if (n == -1L) throw IOException("connection reset")
                                return n
                            }
                        }
                        .buffer()
                }
            }
        }
    }

    private object Online : NetworkConnectivity {
        override fun isOnline() = true

        override fun isMetered() = false

        override fun isRoaming() = false
    }

    private companion object {
        const val ID = 1L
        const val CUT = 3_000
        const val LAST_MODIFIED = "Sat, 14 Nov 2026 22:45:55 GMT"
        val OLD = ByteArray(10_000) { (it % 251).toByte() }
        val NEW = ByteArray(10_000) { (it % 241 + 7).toByte() }
        val NEW_LONGER = ByteArray(12_000) { (it % 239 + 3).toByte() }
        val NEW_SHORTER = ByteArray(8_000) { (it % 233 + 5).toByte() }
    }
}
