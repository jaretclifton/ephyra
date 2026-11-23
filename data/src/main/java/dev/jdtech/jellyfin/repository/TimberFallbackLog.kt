package dev.jdtech.jellyfin.repository

import timber.log.Timber

/** #899: the fallbacks of `StoredFallback.kt` (in application) log to Timber, as they did here. */
internal val TimberFallbackLog = FallbackLog { error, message ->
    // The tag the warnings had in debug builds before #899 (DebugTree names the caller's file).
    Timber.tag("StoredFallbackKt").w(error, message)
}
