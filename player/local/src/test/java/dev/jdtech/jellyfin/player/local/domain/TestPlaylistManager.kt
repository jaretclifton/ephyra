package dev.jdtech.jellyfin.player.local.domain

import android.content.SharedPreferences
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName

/** A [PlaylistManager] over [repository] that resolves playback with [preferences] (#766). */
internal fun playlistManager(
    repository: JellyfinRepository,
    preferences: AppPreferences = testPreferences(),
) = PlaylistManager(repository, ResolvePlayback(repository, preferences))

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
