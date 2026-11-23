package dev.jdtech.jellyfin.player.local.domain

import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.PlayerEngineId
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.testing.asItemIdOrNull
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** CE (#766): the player decides who plays, with which cap, and which source plays. */
class ResolvePlaybackTest {
    private val itemId = UUID.randomUUID().toString().toItemId()

    /** The arguments of each getPlaybackSources call, after the item id. */
    private val requests = mutableListOf<List<Any?>>()

    @Test
    fun `the engine comes from the backend preference`() {
        val resolve = resolve(BACKEND to "mpv")

        assertEquals(PlaybackTarget.LocalPlayer(PlayerEngineId.MPV, true), resolve.localTarget())
    }

    @Test
    fun `the defaults are ExoPlayer with Dolby Vision direct play and no cap`() {
        val resolve = resolve()

        assertEquals(
            PlaybackTarget.LocalPlayer(PlayerEngineId.EXOPLAYER, true),
            resolve.localTarget(),
        )
        assertNull(resolve.maxBitrate())
    }

    @Test
    fun `an unknown backend value plays on ExoPlayer`() {
        val resolve = resolve(BACKEND to "vlc")

        assertEquals(PlayerEngineId.EXOPLAYER, resolve.localTarget().engine)
    }

    @Test
    fun `the Dolby Vision flag comes from its preference`() {
        val resolve = resolve(DOLBY_VISION to false)

        assertEquals(false, resolve.localTarget().directPlayDolbyVision)
    }

    @Test
    fun `a cap of 0 means no cap`() {
        assertNull(resolve(MAX_BITRATE to 0).maxBitrate())
        assertEquals(8_000_000, resolve(MAX_BITRATE to 8_000_000).maxBitrate())
    }

    @Test
    fun `sources asks with the target and the cap the settings have`() {
        val resolve = resolve(BACKEND to "mpv", MAX_BITRATE to 4_000_000, DOLBY_VISION to false)

        runBlocking { resolve.sources(itemId, "source", audioStreamIndex = 2) }

        assertEquals(
            listOf(
                listOf<Any?>(
                    PlaybackTarget.LocalPlayer(PlayerEngineId.MPV, false),
                    "source",
                    2,
                    4_000_000,
                )
            ),
            requests,
        )
    }

    @Test
    fun `sources passes no source, no audio and no cap when there are none`() {
        val resolve = resolve()

        runBlocking { resolve.sources(itemId) }

        assertEquals(
            listOf(
                listOf<Any?>(
                    PlaybackTarget.LocalPlayer(PlayerEngineId.EXOPLAYER, true),
                    null,
                    null,
                    null,
                )
            ),
            requests,
        )
    }

    @Test
    fun `a finished download wins over the server's source`() {
        val download = source("source", FindroidSourceType.LOCAL, "/films/film.mkv")
        val remote = source("source", FindroidSourceType.REMOTE, "https://server/film")

        assertEquals(download, resolve().pick(listOf(remote, download), "source"))
        assertEquals(download, resolve().pick(listOf(remote, download), null))
    }

    @Test
    fun `an unfinished download is skipped`() {
        val partial = source("source", FindroidSourceType.LOCAL, "/films/film.mkv.download")
        val remote = source("source", FindroidSourceType.REMOTE, "https://server/film")

        assertEquals(remote, resolve().pick(listOf(partial, remote), "source"))
        assertNull(resolve().pick(listOf(partial), "source"))
    }

    @Test
    fun `the source with the asked id plays, or the first one when none has it`() {
        val first = source("first", FindroidSourceType.REMOTE, "https://server/first")
        val second = source("second", FindroidSourceType.REMOTE, "https://server/second")

        assertEquals(second, resolve().pick(listOf(first, second), "second"))
        assertEquals(first, resolve().pick(listOf(first, second), "other"))
        assertEquals(first, resolve().pick(listOf(first, second), null))
    }

    @Test
    fun `no sources gives null`() {
        assertNull(resolve().pick(emptyList(), null))
        assertNull(resolve().pick(emptyList(), "source"))
    }

    private fun resolve(vararg values: Pair<String, Any>): ResolvePlayback {
        val repository =
            fakeProxy(
                JellyfinRepository::class.java.classLoader,
                arrayOf(JellyfinRepository::class.java),
            ) { _, method, args ->
                when (method.kotlinName) {
                    "getPlaybackSources" -> {
                        assertEquals(itemId, args[0].asItemIdOrNull())
                        // The last argument is the continuation.
                        requests += args.toList().subList(1, args.size - 1)
                        emptyList<FindroidSource>()
                    }
                    else -> error("Unexpected call to ${method.kotlinName}")
                }
            }
                as JellyfinRepository
        return ResolvePlayback(repository, testPreferences(values.toMap()))
    }

    private fun source(id: String, type: FindroidSourceType, path: String) =
        FindroidSource(
            id = id,
            name = id,
            type = type,
            path = path,
            size = 0L,
            mediaStreams = emptyList(),
        )

    private companion object {
        const val BACKEND = "pref_player_backend"
        const val MAX_BITRATE = "pref_player_max_bitrate"
        const val DOLBY_VISION = "pref_player_direct_play_dovi"
    }
}
