package dev.jdtech.jellyfin.core.presentation.dummy

import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidMediaStream
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.MediaStreamType
import java.time.LocalDateTime

val dummyEpisode =
    FindroidEpisode(
        id = randomItemId(),
        name = "Mother and Children",
        originalTitle = null,
        overview =
            "Stories are lies meant to entertain, and idols lie to fans eager to believe. This is Ai’s story. It is a lie, but it is also true.",
        indexNumber = 1,
        indexNumberEnd = null,
        parentIndexNumber = 1,
        sources =
            listOf(
                FindroidSource(
                    id = "",
                    name = "",
                    type = FindroidSourceType.REMOTE,
                    path = "",
                    size = 0L,
                    mediaStreams =
                        listOf(
                            FindroidMediaStream(
                                title = "",
                                displayTitle = "",
                                language = "en",
                                type = MediaStreamType.VIDEO,
                                codec = "hevc",
                                profile = null,
                                isExternal = false,
                                path = "",
                                channelLayout = null,
                                videoRangeType = null,
                                height = 1080,
                                width = 1920,
                                videoDoViTitle = null,
                            )
                        ),
                )
            ),
        played = true,
        favorite = true,
        canPlay = true,
        canDownload = true,
        runtimeTicks = 2000000000L,
        playbackPositionTicks = 1200000000L,
        premiereDate = LocalDateTime.parse("2019-02-14T00:00:00"),
        seriesId = randomItemId(),
        seriesName = "Oshi no Ko",
        seasonId = randomItemId(),
        seasonName = "Season 1",
        communityRating = 9.2f,
        people = emptyList(),
        images = FindroidImages(),
        chapters = emptyList(),
        trickplayInfo = null,
    )

val dummyEpisodes = listOf(dummyEpisode)
