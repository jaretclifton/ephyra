package dev.jdtech.jellyfin.player.local.domain

import android.content.SharedPreferences
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.QualityNetwork
import dev.jdtech.jellyfin.settings.presentation.enums.AppVariant
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import dev.jdtech.jellyfin.utils.NetworkConnectivity

/** A [PlaylistManager] over [repository] that resolves playback with [preferences] (#766). */
internal fun playlistManager(
    repository: MediaRepository,
    preferences: AppPreferences = testPreferences(),
) = PlaylistManager(repository, resolvePlayback(repository, preferences))

/** A [ResolvePlayback] over [repository] and [preferences], on [network], in [appVariant]. */
internal fun resolvePlayback(
    repository: MediaRepository,
    preferences: AppPreferences = testPreferences(),
    network: QualityNetwork = QualityNetwork.WIFI,
    appVariant: AppVariant = AppVariant.PHONE,
) = ResolvePlayback(repository, preferences, connectivity(network), appVariant)

/** Connectivity that is online on [network], metered on mobile data. */
internal fun connectivity(network: QualityNetwork): NetworkConnectivity =
    object : NetworkConnectivity {
        override fun isOnline() = true

        override fun isMetered() = network == QualityNetwork.MOBILE

        override fun isRoaming() = false
    }

/** Preferences that read [values] by key and the default of every other preference. */
internal fun testPreferences(values: Map<String, Any> = emptyMap()): AppPreferences =
    AppPreferences(
        fakeProxy(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.kotlinName) {
                "getBoolean",
                "getString",
                "getInt",
                "getLong",
                "getFloat",
                "getStringSet" -> values[args!![0] as String] ?: args[1]
                else ->
                    throw UnsupportedOperationException("SharedPreferences.${method.kotlinName}")
            }
        }
            as SharedPreferences
    )
