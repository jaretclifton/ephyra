package dev.jdtech.jellyfin.utils.download

import android.content.SharedPreferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Response

/**
 * CE (#850): what the server said about the file a partial download was written from: its validator
 * for `If-Range` (a strong ETag, else Last-Modified) and its full length. A resume sends the
 * validator, so a server whose file changed answers 200 with the new file instead of appending its
 * bytes to the old ones, and the length catches a changed file when there is no validator.
 */
data class ResumeValidator(val ifRange: String?, val totalBytes: Long) {
    val isEmpty: Boolean
        get() = ifRange == null && totalBytes <= 0L
}

/**
 * Where the engine keeps a [ResumeValidator] per partial file, so a resume after the app was killed
 * still has it. Keyed by the file: a validator is written with every response that starts the file
 * at byte 0, so the one kept always belongs to the bytes on disk.
 */
interface ResumeValidatorStore {
    fun get(file: File): ResumeValidator?

    /** Stores [validator] for [file], or forgets the file when it is null or empty. */
    fun put(file: File, validator: ResumeValidator?)
}

/** For tests, and for an engine built by hand: forgotten when the process ends. */
class InMemoryResumeValidatorStore : ResumeValidatorStore {
    private val validators = ConcurrentHashMap<String, ResumeValidator>()

    override fun get(file: File): ResumeValidator? = validators[file.absolutePath]

    override fun put(file: File, validator: ResumeValidator?) {
        if (validator == null || validator.isEmpty) validators.remove(file.absolutePath)
        else validators[file.absolutePath] = validator
    }
}

/** The app's store: a preferences file, so no Room schema change is needed. */
class SharedPreferencesResumeValidatorStore(private val preferences: SharedPreferences) :
    ResumeValidatorStore {
    override fun get(file: File): ResumeValidator? {
        val stored = preferences.getString(file.absolutePath, null) ?: return null
        val total = stored.substringBefore('\n').toLongOrNull() ?: return null
        val ifRange = stored.substringAfter('\n', "").ifEmpty { null }
        return ResumeValidator(ifRange, total)
    }

    override fun put(file: File, validator: ResumeValidator?) {
        val editor = preferences.edit()
        if (validator == null || validator.isEmpty) {
            editor.remove(file.absolutePath)
        } else {
            editor.putString(
                file.absolutePath,
                "${validator.totalBytes}\n${validator.ifRange ?: ""}",
            )
        }
        editor.apply()
    }
}

/**
 * The validator [response] gives for `If-Range`: a strong ETag, else Last-Modified. A weak ETag
 * never matches in `If-Range`, so it would restart every resume.
 */
internal fun ifRangeValidatorOf(response: Response): String? {
    val etag = response.header("ETag")?.trim()
    if (!etag.isNullOrEmpty() && !etag.startsWith("W/")) return etag
    return response.header("Last-Modified")?.trim()?.ifEmpty { null }
}

/** The start offset in a `Content-Range: bytes <start>-<end>/<total>` header, or -1. */
internal fun parseContentRangeStart(header: String?): Long {
    if (header == null) return -1L
    val range = header.trim().removePrefix("bytes").trim()
    return range.substringBefore('-', "").trim().toLongOrNull() ?: -1L
}

/**
 * CE (#850): whether a 206 or 416 answer to a resume at [existingLength] belongs to the file the
 * partial was written from, as far as [stored] and the answer tell. False means start over: a
 * server or proxy that ignores `If-Range` answers from a changed file this way.
 */
internal fun resumeMatches(
    code: Int,
    existingLength: Long,
    stored: ResumeValidator?,
    responseValidator: String?,
    contentRangeStart: Long,
    contentRangeTotal: Long,
): Boolean {
    if (
        stored?.ifRange != null && responseValidator != null && stored.ifRange != responseValidator
    ) {
        return false
    }
    if (stored != null && stored.totalBytes > 0 && contentRangeTotal > 0) {
        if (stored.totalBytes != contentRangeTotal) return false
    }
    return when (code) {
        206 -> contentRangeStart < 0 || contentRangeStart == existingLength
        // Complete only when the file on disk is as long as the server's.
        416 -> contentRangeTotal <= 0 || contentRangeTotal == existingLength
        else -> true
    }
}
