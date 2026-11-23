package dev.jdtech.jellyfin.core.presentation.dummy

import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.MediaStream
import dev.jdtech.jellyfin.models.MediaStreamType
import dev.jdtech.jellyfin.models.toMediaSourceId
import java.time.LocalDateTime

val dummyEpisode =
    Episode(
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
                ItemSource(
                    id = "".toMediaSourceId(),
                    name = "",
                    type = ItemSourceType.REMOTE,
                    path = "",
                    size = 0L,
                    mediaStreams =
                        listOf(
                            MediaStream(
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
        images = ItemImages(),
        chapters = emptyList(),
        trickplayInfo = null,
    )

val dummyEpisodes = listOf(dummyEpisode)
