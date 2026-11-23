package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.models.BoxSet
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.ItemTrickplayInfo
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.models.MediaStream
import dev.jdtech.jellyfin.models.MediaStreamType
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.Person
import dev.jdtech.jellyfin.models.Season
import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.models.SegmentType
import dev.jdtech.jellyfin.models.ServerInfo
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.SubtitleDeliveryMethod
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toUserId

/**
 * The library every implementation of the server ports presents to the contract suites (#771).
 * Small on purpose.
 *
 * It is taken from the Docker test server (`docs/dev-environment.md`): the names, runtimes and
 * seasons are the server's, so the Jellyfin adapter's recordings and a live run against that server
 * present the same library. Ids are fixed: the recordings carry these instead of the server's, and
 * a live run translates between the two.
 *
 * What the test server does not have ([Part]) is hand-written in the recordings and skipped in a
 * live run.
 */
object ContractLibrary : FakeLibrary {
    /** What a contract test needs that not every server holds. */
    enum class Part {
        /** [boxSet]: the test server has no collections. */
        BOX_SET,
        /** [person]. */
        PERSON,
        /** [episodeWithSegments]: the test server has no segment provider. */
        SEGMENTS,
        /** A theme song of [series]: the test server has none. */
        THEME_SONG,
        /**
         * Items similar to [movieWithResume]: the test server finds none among its test patterns.
         */
        SIMILAR_ITEMS,
    }

    /** Ids start with twelve zeros, the mark of a fake id in the recordings. */
    private fun id(n: Int): ItemId = idText(n).toItemId()

    private fun idText(n: Int): String = "00000000-0000-0000-0000-%012d".format(n)

    /** No item of any server has it. */
    val unknownId: ItemId = "00000000-0000-0000-0000-00000000dead".toItemId()

    override val userId: UserId = idText(1).toUserId()

    const val USER_NAME = "contract"

    /** The test server's `contract` user has no password. */
    const val PASSWORD = ""

    const val WRONG_PASSWORD = "wrong"

    override val server =
        ServerInfo(
            id = "00000000000000000000000000000002",
            name = "Ephyra Test",
            version = "12.4.0",
            product = "Jellyfin Server",
        )

    override val address = "http://fake.server"

    override val users = mapOf(USER_NAME to PASSWORD)

    private const val MINUTE = 600_000_000L

    val moviesLibrary =
        Library(id = id(11), name = "Movies", type = CollectionType.Movies, images = ItemImages())

    val showsLibrary =
        Library(id = id(12), name = "Shows", type = CollectionType.TvShows, images = ItemImages())

    /** Long Movie: twelve minutes, resumed at two (20 %). */
    val movieWithResume =
        movie(
            id = id(101),
            name = "Long Movie (2016)",
            runtimeTicks = 7_200_230_000L,
            playbackPositionTicks = 2 * MINUTE,
            bitrate = 428_657,
        )

    val favoriteMovie =
        movie(
            id = id(102),
            name = "Plain Movie (2020)",
            runtimeTicks = 3_000_230_000L,
            favorite = true,
            bitrate = 1_867_445,
        )

    /** Two versions of one movie: two sources. */
    val movieWithTwoVersions =
        movie(id = id(103), name = "Versions Movie (2018)", runtimeTicks = 600_230_000L).let { movie
            ->
            movie.copy(
                sources =
                    listOf(
                        source(movie.id.toN(), "720p", bitrate = 3_888_000),
                        source(id(104).toN(), "360p", bitrate = 1_866_000),
                    )
            )
        }

    /**
     * An item with an external subtitle. A movie: the test server has no episode with one (#771
     * asked for an episode; the contract only needs an item).
     */
    val movieWithExternalSubtitle =
        movie(id = id(105), name = "Tracks Movie (2022)", runtimeTicks = 1_200_230_000L).let { movie
            ->
            movie.copy(
                sources =
                    listOf(
                        source(movie.id.toN(), movie.name, bitrate = 2_290_000)
                            .copy(
                                mediaStreams =
                                    listOf(
                                        stream(MediaStreamType.SUBTITLE, "subrip", 0, true),
                                        stream(MediaStreamType.VIDEO, "h264", 3, false),
                                        stream(MediaStreamType.AUDIO, "aac", 4, false),
                                        stream(MediaStreamType.AUDIO, "ac3", 5, false),
                                    )
                            )
                    )
            )
        }

    /** Test Show: Specials, two seasons with episodes and an empty Season 3. S01E01 is played. */
    val series =
        Show(
            id = id(201),
            name = "Test Show",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            seasons = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            unplayedItemCount = 7,
            genres = emptyList(),
            people = emptyList(),
            runtimeTicks = 0L,
            communityRating = null,
            officialRating = null,
            status = "",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = ItemImages(),
        )

    val specials = season(id(210), "Specials", 0)
    val season1 = season(id(211), "Season 1", 1)
    val season2 = season(id(212), "Season 2", 2)
    val season3 = season(id(213), "Season 3", 3)

    val seasons = listOf(specials, season1, season2, season3)

    val special = episode(id(300), specials, 1, "Special", runtimeTicks = 300_230_000L)

    /** Season 1, in episode order. The first is played. */
    val season1Episodes =
        (1..5).map { n -> episode(id(310 + n), season1, n, "Episode 0$n", played = n == 1) }

    val season2Episodes = (1..2).map { n -> episode(id(320 + n), season2, n, "Episode 0$n") }

    /** The first unplayed episode after the played one. */
    val nextUp: Episode = season1Episodes[1]

    val episodeWithSegments: Episode = season1Episodes[1]

    /** In milliseconds, as `Segment` keeps them. */
    val introOfEpisodeWithSegments = Segment(SegmentType.INTRO, 0L, 30_000L)

    val boxSet = BoxSet(id = id(501), name = "Contract Collection", images = ItemImages())

    val person = Person(id = id(401), name = "Bryan Cranston", overview = "", images = ItemImages())

    val movies =
        listOf(movieWithResume, favoriteMovie, movieWithTwoVersions, movieWithExternalSubtitle)

    val episodes: List<Episode> = listOf(special) + season1Episodes + season2Episodes

    override val libraries = listOf(moviesLibrary, showsLibrary)

    override val items: List<Item> = movies + series + seasons + episodes + boxSet

    override val children =
        mapOf(
            moviesLibrary.id to movies.map { it.id },
            showsLibrary.id to listOf(series.id),
            boxSet.id to listOf(movieWithResume.id, favoriteMovie.id),
        )

    override val people = listOf(person)

    override val personItems: Map<ItemId, List<ItemId>> = mapOf(person.id to emptyList())

    override val segments = mapOf(episodeWithSegments.id to listOf(introOfEpisodeWithSegments))

    /** The theme song of [series]. */
    val seriesThemeSongId: ItemId = id(602)

    override val themeSongs: Map<ItemId, String> =
        mapOf(series.id to "$address/Audio/${seriesThemeSongId.asString()}/stream?static=true")

    /** The form of an id in a server's JSON and in a source id: no dashes. */
    fun ItemId.toN(): String = asString().replace("-", "")

    private fun movie(
        id: ItemId,
        name: String,
        runtimeTicks: Long,
        playbackPositionTicks: Long = 0L,
        favorite: Boolean = false,
        bitrate: Int? = null,
    ) =
        Movie(
            id = id,
            name = name,
            originalTitle = null,
            overview = "",
            sources = listOf(source(id.toN(), name, bitrate)),
            played = false,
            favorite = favorite,
            canPlay = true,
            canDownload = true,
            runtimeTicks = runtimeTicks,
            playbackPositionTicks = playbackPositionTicks,
            premiereDate = null,
            people = emptyList(),
            genres = emptyList(),
            communityRating = null,
            officialRating = null,
            status = "",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = ItemImages(),
            chapters = emptyList(),
            trickplayInfo = trickplay(id),
        )

    private fun season(id: ItemId, name: String, index: Int) =
        Season(
            id = id,
            name = name,
            seriesId = series.id,
            seriesName = series.name,
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            indexNumber = index,
            episodes = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            unplayedItemCount = null,
            images = ItemImages(),
        )

    private fun episode(
        id: ItemId,
        season: Season,
        index: Int,
        title: String,
        played: Boolean = false,
        runtimeTicks: Long = 900_230_000L,
    ): Episode {
        val name = "Test Show - S%02dE%02d - %s".format(season.indexNumber, index, title)
        return Episode(
            id = id,
            name = name,
            originalTitle = null,
            overview = "",
            indexNumber = index,
            indexNumberEnd = null,
            parentIndexNumber = season.indexNumber,
            sources = listOf(source(id.toN(), name, bitrate = 1_866_000)),
            played = played,
            favorite = false,
            canPlay = true,
            canDownload = true,
            runtimeTicks = runtimeTicks,
            playbackPositionTicks = 0L,
            premiereDate = null,
            seriesId = series.id,
            seriesName = series.name,
            seasonId = season.id,
            seasonName = season.name,
            communityRating = null,
            people = emptyList(),
            images = ItemImages(),
            chapters = emptyList(),
            trickplayInfo = trickplay(id),
        )
    }

    private fun source(id: String, name: String, bitrate: Int?) =
        ItemSource(
            id = id,
            name = name,
            type = ItemSourceType.REMOTE,
            path = "",
            size = 0L,
            mediaStreams =
                listOf(
                    stream(MediaStreamType.VIDEO, "h264", 0, false),
                    stream(MediaStreamType.AUDIO, "aac", 1, false),
                ),
            container = "mkv",
            bitrate = bitrate,
        )

    private fun stream(type: MediaStreamType, codec: String, index: Int, external: Boolean) =
        MediaStream(
            title = "",
            displayTitle = null,
            language = "",
            type = type,
            codec = codec,
            profile = null,
            isExternal = external,
            path = null,
            channelLayout = null,
            videoRangeType = null,
            height = null,
            width = null,
            videoDoViTitle = null,
            index = index,
            deliveryMethod = if (external) SubtitleDeliveryMethod.EXTERNAL else null,
        )

    /** The test server makes trickplay images 320 wide, ten seconds apart. */
    private fun trickplay(id: ItemId) =
        mapOf(id.toN() to ItemTrickplayInfo(320, 180, 10, 10, 9, 10_000, 892))
}
