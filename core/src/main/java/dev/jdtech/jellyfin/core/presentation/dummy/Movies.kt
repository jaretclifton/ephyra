package dev.jdtech.jellyfin.core.presentation.dummy

import dev.jdtech.jellyfin.models.ExternalLink
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.MediaStream
import dev.jdtech.jellyfin.models.MediaStreamType
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.toMediaSourceId
import java.time.LocalDateTime

val dummyMovie =
    Movie(
        id = randomItemId(),
        name = "Alita: Battle Angel",
        originalTitle = null,
        overview =
            "When Alita awakens with no memory of who she is in a future world she does not recognize, she is taken in by Ido, a compassionate doctor who realizes that somewhere in this abandoned cyborg shell is the heart and soul of a young woman with an extraordinary past.",
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
        played = false,
        favorite = true,
        canPlay = true,
        canDownload = true,
        runtimeTicks = 2000000000L,
        playbackPositionTicks = 0L,
        premiereDate = LocalDateTime.parse("2019-02-14T00:00:00"),
        people = emptyList(),
        genres = listOf("Action", "Sience Fiction", "Adventure"),
        communityRating = 7.2f,
        officialRating = "PG-13",
        status = "Ended",
        productionYear = 2019,
        endDate = null,
        trailer = "https://www.youtube.com/watch?v=puKWa8hrvA8",
        tagline = "An angel falls. A warrior rises.",
        criticRating = 61f,
        studios = listOf("20th Century Fox", "Lightstorm Entertainment"),
        externalLinks =
            listOf(
                ExternalLink("IMDb", "https://www.imdb.com/title/tt0437086"),
                ExternalLink("TheMovieDb", "https://www.themoviedb.org/movie/399579"),
            ),
        playCount = 2,
        images = ItemImages(),
        chapters = emptyList(),
        trickplayInfo = null,
    )

val dummyMovies = listOf(dummyMovie)
