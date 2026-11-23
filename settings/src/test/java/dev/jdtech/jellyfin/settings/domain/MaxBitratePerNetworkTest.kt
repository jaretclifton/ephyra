package dev.jdtech.jellyfin.settings.domain

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/** CE (#837): the one streaming quality becomes the one for Wi-Fi and for mobile data. */
class MaxBitratePerNetworkTest {
    private val stored = mutableMapOf<String, Any?>()

    /** SharedPreferences over [stored]: contains, getInt, and an editor that puts Ints. */
    private val sharedPreferences =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) {
            _,
            method,
            args ->
            when (method.name) {
                "contains" -> stored.containsKey(args!![0])
                "getInt" -> stored[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> error("not used: ${method.name}")
            }
        } as SharedPreferences

    private fun editor(): SharedPreferences.Editor {
        val put = mutableMapOf<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "putInt" -> {
                        put[args!![0] as String] = args[1]
                        editor
                    }
                    "apply" -> stored.putAll(put)
                    else -> error("not used: ${method.name}")
                }
            } as SharedPreferences.Editor
        return editor
    }

    private val preferences = AppPreferences(sharedPreferences)

    @Test
    fun `a stored quality becomes the quality on both networks`() {
        stored[WIFI] = 8_000_000

        preferences.migrateMaxBitratePerNetwork()

        assertEquals(mapOf<String, Any?>(WIFI to 8_000_000, MOBILE to 8_000_000), stored)
        assertEquals(8_000_000, preferences.getValue(preferences.maxBitrate(QualityNetwork.WIFI)))
        assertEquals(8_000_000, preferences.getValue(preferences.maxBitrate(QualityNetwork.MOBILE)))
    }

    @Test
    fun `without a stored quality both networks play the original`() {
        preferences.migrateMaxBitratePerNetwork()

        assertEquals(mapOf<String, Any?>(MOBILE to 0), stored)
        assertEquals(0, preferences.getValue(preferences.maxBitrate(QualityNetwork.WIFI)))
    }

    @Test
    fun `a quality chosen for mobile data is kept`() {
        stored[WIFI] = 0
        stored[MOBILE] = 720_000

        // Runs on every start: the second time and later it must change nothing.
        preferences.migrateMaxBitratePerNetwork()

        assertEquals(mapOf<String, Any?>(WIFI to 0, MOBILE to 720_000), stored)
    }

    @Test
    fun `each network has its own preference`() {
        assertEquals(WIFI, preferences.maxBitrate(QualityNetwork.WIFI).backendName)
        assertEquals(MOBILE, preferences.maxBitrate(QualityNetwork.MOBILE).backendName)
    }

    @Test
    fun `a metered network is mobile data`() {
        assertEquals(QualityNetwork.MOBILE, QualityNetwork.of(metered = true))
        assertEquals(QualityNetwork.WIFI, QualityNetwork.of(metered = false))
    }

    private companion object {
        const val WIFI = "pref_player_max_bitrate"
        const val MOBILE = "pref_player_max_bitrate_mobile"
    }
}
