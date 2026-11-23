package dev.jdtech.jellyfin.utils

import dev.jdtech.jellyfin.repository.FallbackLog
import timber.log.Timber

/**
 * #899: the stored-copy fallbacks of `application` (`serverOrStored`), for the screens, log to
 * Timber as they did when they were in data.
 */
val TimberFallbackLog = FallbackLog { error, message ->
    // The tag the warnings had in debug builds before #899 (DebugTree names the caller's file).
    Timber.tag("StoredFallbackKt").w(error, message)
}
