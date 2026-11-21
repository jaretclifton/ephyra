package dev.jdtech.jellyfin.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerEngineIdTest {

    @Test
    fun `every preferenceValue round-trips through fromPreference`() {
        for (engine in PlayerEngineId.entries) {
            assertEquals(engine, PlayerEngineId.fromPreference(engine.preferenceValue))
        }
    }

    @Test
    fun `fromPreference reads the stored values`() {
        assertEquals(PlayerEngineId.EXOPLAYER, PlayerEngineId.fromPreference("exoplayer"))
        assertEquals(PlayerEngineId.MPV, PlayerEngineId.fromPreference("mpv"))
    }

    @Test
    fun `fromPreference is null for an enum name or an unknown value`() {
        assertNull(PlayerEngineId.fromPreference("MPV"))
        assertNull(PlayerEngineId.fromPreference(""))
        assertNull(PlayerEngineId.fromPreference("vlc"))
    }
}
