package dev.jdtech.jellyfin.utils.download

import android.app.Application
import android.content.Context
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** CE (#850): the validators a resume is checked with, and where they are kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ResumeValidatorsTest {
    private val file = File("/downloads/Movie (2024)/Movie (2024).mkv.download")

    @Test
    fun `the preferences keep a validator across a restart and forget it on request`() {
        val preferences =
            RuntimeEnvironment.getApplication()
                .getSharedPreferences("resume-validators-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val validator = ResumeValidator("Sat, 14 Nov 2026 22:45:55 GMT", 404_124L)

        assertTrue(SharedPreferencesResumeValidatorStore(preferences).put(file, validator))

        // A new store over the same preferences: the app after a restart.
        assertEquals(validator, SharedPreferencesResumeValidatorStore(preferences).get(file))
        val lengthOnly = ResumeValidator(null, 10L)
        SharedPreferencesResumeValidatorStore(preferences).put(file, lengthOnly)
        assertEquals(lengthOnly, SharedPreferencesResumeValidatorStore(preferences).get(file))

        SharedPreferencesResumeValidatorStore(preferences).put(file, null)
        assertNull(SharedPreferencesResumeValidatorStore(preferences).get(file))
        SharedPreferencesResumeValidatorStore(preferences).put(file, ResumeValidator(null, -1L))
        assertNull(SharedPreferencesResumeValidatorStore(preferences).get(file))
    }

    @Test
    fun `a strong ETag is preferred, a weak one is never sent`() {
        assertEquals(
            "\"abc\"",
            ifRangeValidatorOf(response("ETag" to "\"abc\"", "Last-Modified" to DATE)),
        )
        assertEquals(
            DATE,
            ifRangeValidatorOf(response("ETag" to "W/\"abc\"", "Last-Modified" to DATE)),
        )
        assertNull(ifRangeValidatorOf(response("ETag" to "W/\"abc\"")))
        assertNull(ifRangeValidatorOf(response()))
    }

    @Test
    fun `an answer from another file does not continue the partial`() {
        val stored = ResumeValidator(DATE, 1_000L)
        fun matches(
            code: Int = 206,
            validator: String? = DATE,
            start: Long = 400L,
            total: Long = 1_000L,
            known: ResumeValidator? = stored,
        ) = resumeMatches(code, 400L, known, validator, start, total)

        assertTrue(matches())
        assertFalse(matches(validator = "Tue, 17 Nov 2026 08:00:00 GMT"))
        assertFalse(matches(total = 1_200L))
        assertFalse(matches(start = 0L), "a 206 from another offset")
        // Nothing to compare with: what the answer says is taken as it is.
        assertTrue(matches(validator = null, total = -1L, start = -1L))
        assertTrue(matches(known = null, validator = "anything", total = 5_000L))
        // 416: complete only when the partial is as long as the server's file.
        assertTrue(resumeMatches(416, 1_000L, stored, DATE, -1L, 1_000L))
        assertFalse(resumeMatches(416, 1_000L, null, null, -1L, 800L))
        assertTrue(resumeMatches(416, 1_000L, null, null, -1L, -1L))
    }

    @Test
    fun `the start of a Content-Range is read`() {
        assertEquals(400L, parseContentRangeStart("bytes 400-999/1000"))
        assertEquals(-1L, parseContentRangeStart("bytes */1000"))
        assertEquals(-1L, parseContentRangeStart(null))
    }

    private fun response(vararg headers: Pair<String, String>): Response =
        Response.Builder()
            .request(Request.Builder().url("http://server.test/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()

    private companion object {
        const val DATE = "Sat, 14 Nov 2026 22:45:55 GMT"
    }
}
