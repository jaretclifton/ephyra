package dev.jdtech.jellyfin.player.local.domain

import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.PlayerEngineId
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.repository.PlaybackMedia
import dev.jdtech.jellyfin.settings.domain.QualityNetwork
import dev.jdtech.jellyfin.settings.presentation.enums.AppVariant
import dev.jdtech.jellyfin.testing.asItemIdOrNull
import dev.jdtech.jellyfin.testing.fakeRole
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

        runBlocking { resolve.sources(itemId, "source".toMediaSourceId(), audioStreamIndex = 2) }

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
        val download = source("source", ItemSourceType.LOCAL, "/films/film.mkv")
        val remote = source("source", ItemSourceType.REMOTE, "https://server/film")

        assertEquals(download, resolve().pick(listOf(remote, download), "source".toMediaSourceId()))
        assertEquals(download, resolve().pick(listOf(remote, download), null))
    }

    @Test
    fun `an unfinished download is skipped`() {
        val partial = source("source", ItemSourceType.LOCAL, "/films/film.mkv.download")
        val remote = source("source", ItemSourceType.REMOTE, "https://server/film")

        assertEquals(remote, resolve().pick(listOf(partial, remote), "source".toMediaSourceId()))
        assertNull(resolve().pick(listOf(partial), "source".toMediaSourceId()))
    }

    @Test
    fun `the source with the asked id plays, or the first one when none has it`() {
        val first = source("first", ItemSourceType.REMOTE, "https://server/first")
        val second = source("second", ItemSourceType.REMOTE, "https://server/second")

        assertEquals(second, resolve().pick(listOf(first, second), "second".toMediaSourceId()))
        assertEquals(first, resolve().pick(listOf(first, second), "other".toMediaSourceId()))
        assertEquals(first, resolve().pick(listOf(first, second), null))
    }

    @Test
    fun `no sources gives null`() {
        assertNull(resolve().pick(emptyList(), null))
        assertNull(resolve().pick(emptyList(), "source".toMediaSourceId()))
    }

    @Test
    fun `on Wi-Fi the Wi-Fi quality applies`() {
        val resolve = resolve(MAX_BITRATE to 0, MAX_BITRATE_MOBILE to 4_000_000)

        assertEquals(QualityNetwork.WIFI, resolve.qualityNetwork())
        assertNull(resolve.maxBitrate())
    }

    @Test
    fun `on mobile data the mobile quality applies`() {
        val resolve =
            resolve(
                MAX_BITRATE to 0,
                MAX_BITRATE_MOBILE to 4_000_000,
                network = QualityNetwork.MOBILE,
            )

        assertEquals(QualityNetwork.MOBILE, resolve.qualityNetwork())
        assertEquals(4_000_000, resolve.maxBitrate())
        runBlocking { resolve.sources(itemId) }
        assertEquals(4_000_000, requests.single().last(), "the cap asked for")
    }

    @Test
    fun `the TV app keeps its one quality on a metered network`() {
        val resolve =
            resolve(
                MAX_BITRATE to 8_000_000,
                MAX_BITRATE_MOBILE to 720_000,
                network = QualityNetwork.MOBILE,
                appVariant = AppVariant.TV,
            )

        assertEquals(QualityNetwork.WIFI, resolve.qualityNetwork())
        assertEquals(8_000_000, resolve.maxBitrate())
    }

    @Test
    fun `a cap given to sources wins over the setting`() {
        val resolve = resolve(MAX_BITRATE to 8_000_000)

        runBlocking { resolve.sources(itemId, maxBitrate = 720_000) }
        runBlocking { resolve.sources(itemId, maxBitrate = 0) }

        assertEquals(listOf<Any?>(720_000, null), requests.map { it.last() }, "the caps asked for")
    }

    private fun resolve(
        vararg values: Pair<String, Any>,
        network: QualityNetwork = QualityNetwork.WIFI,
        appVariant: AppVariant = AppVariant.PHONE,
    ): ResolvePlayback {
        val media =
            fakeRole<PlaybackMedia> { _, method, args ->
                when (method.kotlinName) {
                    "getPlaybackSources" -> {
                        assertEquals(itemId, args[0].asItemIdOrNull())
                        // The last argument is the continuation.
                        requests += args.toList().subList(1, args.size - 1)
                        emptyList<ItemSource>()
                    }
                    else -> error("Unexpected call to ${method.kotlinName}")
                }
            }
        return resolvePlayback(media, testPreferences(values.toMap()), network, appVariant)
    }

    private fun source(id: String, type: ItemSourceType, path: String) =
        ItemSource(
            id = id.toMediaSourceId(),
            name = id,
            type = type,
            path = path,
            size = 0L,
            mediaStreams = emptyList(),
        )

    private companion object {
        const val BACKEND = "pref_player_backend"
        const val MAX_BITRATE = "pref_player_max_bitrate"
        const val MAX_BITRATE_MOBILE = "pref_player_max_bitrate_mobile"
        const val DOLBY_VISION = "pref_player_direct_play_dovi"
    }
}
