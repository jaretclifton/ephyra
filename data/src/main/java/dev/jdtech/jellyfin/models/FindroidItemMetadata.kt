package dev.jdtech.jellyfin.models

import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * CE (#332): metadata the server already sends in the item response and the detail pages show.
 * Everything here comes from the user's own server; an empty value means the page hides it.
 */

/** A link to the item on an outside site (IMDb, TMDB, TVDB, Trakt…), named after that site. */
data class FindroidExternalLink(val name: String, val url: String)

/** A trailer hosted elsewhere (usually YouTube). [name] is the server's title for it, if any. */
data class FindroidTrailer(val name: String?, val url: String)

/** The first tagline, or null when there is none. */
fun List<String>?.firstTagline(): String? = orEmpty().firstOrNull { it.isNotBlank() }?.trim()

/**
 * The days and time a running show airs, such as "Thursday 21:00", "Mon, Thu 21:00" or, when it
 * airs every day, "[daily] 21:00"; Monday first. Null when the show has ended or the server has no
 * schedule.
 */
fun FindroidShow.airSchedule(locale: Locale, daily: String): String? {
    if (!status.equals("Continuing", ignoreCase = true)) return null
    val days = airDays.sorted()
    val style = if (days.size == 1) TextStyle.FULL else TextStyle.SHORT
    val dayText =
        if (days.size == DayOfWeek.entries.size) daily
        else days.joinToString { it.getDisplayName(style, locale) }
    return listOfNotNull(dayText.ifEmpty { null }, airTime).joinToString(" ").ifEmpty { null }
}
