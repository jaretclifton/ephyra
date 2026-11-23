package dev.jdtech.jellyfin.repository

import timber.log.Timber

/** #899: the fallbacks of `StoredFallback.kt` (in application) log to Timber, as they did here. */
internal val TimberFallbackLog = FallbackLog { error, message -> Timber.w(error, message) }
