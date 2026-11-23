package dev.jdtech.jellyfin.database

import android.app.Application
import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import dev.jdtech.jellyfin.models.ItemSourceEntity
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.MediaStreamEntity
import dev.jdtech.jellyfin.models.MediaStreamId
import dev.jdtech.jellyfin.models.MediaStreamType
import dev.jdtech.jellyfin.models.PendingDownloadDto
import dev.jdtech.jellyfin.models.ServerAddressEntity
import dev.jdtech.jellyfin.models.ServerAddressId
import dev.jdtech.jellyfin.models.ServerEntity
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.models.UserEntity
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.serverType
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.models.toMediaStreamId
import dev.jdtech.jellyfin.models.toServer
import dev.jdtech.jellyfin.models.toServerAddressId
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CE (#380): every released schema migrates to the current one, and the rows a user cannot get back
 * survive it: the server and its address, the user with the access token and profile lock, the
 * download metadata (items, sources, streams, pending downloads), the offline resume position and
 * the series track preferences.
 *
 * The target is the highest schema in `data/schemas/`, not a fixed number. A new version that has
 * no migration fails every test here with "no migration path"; in the app it would hit
 * `fallbackToDestructiveMigration` and silently wipe the database instead.
 *
 * CE (#633): the offline position's row also gets a last played time when it waits to be sent.
 *
 * CE (#693): a downloaded item's skip segments survive the change of the segments table's key, and
 * those of an item that is no longer downloaded go.
 *
 * CE (#770): every saved server becomes a Jellyfin server, the only type there was before the
 * column, and the tables keep their keys and indexes.
 *
 * Released schemas: 12 (v1.2.0-ce.0 to ce.3), 14 (v1.2.0-ce.4 to 2.1.0), 16 (2.2.0), 18 (2.3.0 to
 * 2.5.0), 19 (2.6.0, 2.7.0), 20 (2.8.0).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServerDatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dbFile = context.getDatabasePath("migration-test")

    @get:Rule val helper = helper(dbFile)

    private val latest: Int =
        context.assets.list(SCHEMA_DIR)!!.map { it.removeSuffix(".json").toInt() }.max()

    @Test fun `schema 12 migrates with its data`() = migratesWithData(12)

    @Test fun `schema 14 migrates with its data`() = migratesWithData(14)

    @Test fun `schema 16 migrates with its data`() = migratesWithData(16)

    @Test fun `schema 18 migrates with its data`() = migratesWithData(18)

    @Test fun `schema 19 migrates with its data`() = migratesWithData(19)

    @Test fun `schema 20 migrates with its data`() = migratesWithData(20)

    /**
     * CE (#770): two saved servers, each with its address, user and download, all survive the
     * migration that adds the server type, and both servers are Jellyfin servers. The type is the
     * only change: every table keeps its columns, keys, foreign keys and indexes.
     */
    @Test
    fun `schema 20 with two servers migrates, both typed jellyfin`() =
        runBlocking<Unit> {
            val before =
                helper.createDatabase(20).run {
                    insertRows(20)
                    insertSecondServer()
                    val described = describeSchema()
                    close()
                    described
                }

            helper.runMigrationsAndValidate(latest, MIGRATIONS).apply {
                assertEquals(
                    listOf(SERVER to "jellyfin", SECOND_SERVER to "jellyfin"),
                    rows("SELECT id, type FROM servers ORDER BY id"),
                )
                assertEquals(
                    listOf(ADDRESS_ID to SERVER, SECOND_ADDRESS_ID to SECOND_SERVER),
                    rows("SELECT id, serverId FROM serverAddresses ORDER BY id"),
                )
                assertEquals(
                    listOf(USER to TOKEN, SECOND_USER to SECOND_TOKEN),
                    rows("SELECT id, accessToken FROM users ORDER BY id"),
                )
                assertEquals(
                    listOf(USER to ADDRESS_ID, SECOND_USER to SECOND_ADDRESS_ID),
                    rows(
                        "SELECT currentUserId, currentServerAddressId FROM servers ORDER BY currentUserId"
                    ),
                )
                assertEquals(
                    listOf(MOVIE to SERVER, SECOND_MOVIE to SECOND_SERVER),
                    rows("SELECT id, serverId FROM movies ORDER BY id"),
                )
                assertEquals(
                    listOf("second-source" to SECOND_MOVIE, "source" to MOVIE),
                    rows("SELECT id, itemId FROM sources ORDER BY id"),
                )
                assertEquals(2L, long("SELECT COUNT(*) FROM userdata WHERE toBeSynced = 1"))

                val after = describeSchema()
                // The description does see the foreign keys and indexes it is compared on.
                assertTrue(before.any { "users foreign key servers serverId id" in it }, "$before")
                assertTrue(before.any { "users index index_users_serverId" in it }, "$before")
                assertEquals(
                    before,
                    after - SERVER_TYPE_COLUMN,
                    message = "the migration changed more than the servers table's new column",
                )
                assertTrue(SERVER_TYPE_COLUMN in after, message = "no type column: $after")
                close()
            }
        }

    /**
     * CE (#770): an install that starts at the current version gets the same tables, keys and
     * indexes as one that migrated to it, the new column with its default included.
     */
    @Test
    fun `a fresh database has the schema of a migrated one`() = runBlocking {
        helper.createDatabase(20).close()
        val migrated =
            helper.runMigrationsAndValidate(latest, MIGRATIONS).run {
                val described = describeSchema()
                close()
                described
            }

        // Created by Room from the compiled entities, the way a new install creates it.
        val freshFile = context.getDatabasePath("fresh-install")
        freshFile.delete()
        val database =
            Room.databaseBuilder(context, ServerDatabase::class.java, freshFile.path)
                .setDriver(AndroidSQLiteDriver())
                .build()
        try {
            database.getServerDatabaseDao().getServers()
        } finally {
            database.close()
        }
        val fresh =
            AndroidSQLiteDriver().open(freshFile.path).run {
                val described = describeSchema()
                close()
                described
            }

        assertEquals(migrated, fresh)
    }

    /** Every exported schema, released or not, has a migration path to the current one. */
    @Test
    fun `every exported schema migrates to the current one`() = runBlocking {
        for (version in 2 until latest) {
            val file = context.getDatabasePath("sweep-$version")
            val sweep = helper(file)
            sweep.createDatabase(version).close()
            sweep.runMigrationsAndValidate(latest, MIGRATIONS).close()
        }
    }

    /**
     * KSP rewrites the current version's JSON in place when an entity changes without a version
     * bump, and the build passes. The migration tests then compare against the rewritten file and
     * stay green, while an installed database of that version no longer matches the code.
     *
     * When this fails: undo the change to the schema file, bump `version` in [ServerDatabase] and
     * add a migration (an `AutoMigration` usually does). Never change a hash here: each one is a
     * schema that is on users' devices.
     */
    @Test
    fun `released schemas are unchanged`() {
        val pinned =
            mapOf(
                12 to "9a46630a7d4c4ba4dfd46b2d85afc52f",
                14 to "e90f3d1b1d47c59c2e974df07965d328",
                16 to "ca0feb86ab6251711afc43b6a5f55957",
                18 to "8f1beae8cd83dda5184f02488860efa0",
                19 to "4cf93f4ec8d7317665467ea0a098e807",
                20 to "122265e99eedfa45c1c8afde729e82b1",
            )
        for ((version, hash) in pinned) {
            val json =
                context.assets.open("$SCHEMA_DIR/$version.json").bufferedReader().use {
                    it.readText()
                }
            val actual = Regex("\"identityHash\": \"([0-9a-f]+)\"").find(json)!!.groupValues[1]
            assertEquals(hash, actual, message = "identity hash of schema $version")
        }
    }

    /**
     * CE (#773): item and user ids became value classes over their text, and Room binds them by
     * that text, with no converter and no schema change. A schema 21 database as the `UUID`
     * converter wrote it (dashed lowercase text) reads back as the same ids, and an id the DAO
     * writes is stored as that same text.
     */
    @Test
    fun `ids of a schema 21 database read and write as the text the UUID converter stored`() =
        runBlocking {
            helper.createDatabase(21).apply {
                insertRows(21)
                close()
            }
            val database =
                Room.databaseBuilder(context, ServerDatabase::class.java, dbFile.path)
                    .setDriver(AndroidSQLiteDriver())
                    .addMigrations(*MIGRATIONS.toTypedArray())
                    .build()
            try {
                val dao = database.getServerDatabaseDao()
                assertEquals(USER.toUserId(), dao.getServerCurrentUser(SERVER.toServerId())!!.id)
                assertEquals(USER.toUserId(), dao.getServer(SERVER.toServerId())!!.currentUserId)
                assertEquals(MOVIE.toItemId(), dao.getMovie(MOVIE.toItemId()).id)
                assertEquals(MOVIE.toItemId(), dao.getSources(MOVIE.toItemId()).single().itemId)
                val episode = dao.getEpisode(EPISODE.toItemId())
                assertEquals(SEASON.toItemId(), episode.seasonId)
                assertEquals(SHOW.toItemId(), episode.seriesId)
                val userData = dao.getUserData(MOVIE.toItemId(), USER.toUserId())!!
                assertEquals(
                    USER.toUserId() to MOVIE.toItemId(),
                    userData.userId to userData.itemId,
                )
                assertEquals(EPISODE.toItemId(), dao.getPendingDownloads().single().itemId)

                dao.insertUser(
                    UserEntity(
                        id = NEW_USER.toUserId(),
                        name = "Bob",
                        serverId = SERVER.toServerId(),
                    )
                )
                dao.insertPendingDownload(
                    PendingDownloadDto(
                        itemId = NEW_ITEM.toItemId(),
                        itemKind = "Movie",
                        addedAt = 1L,
                    )
                )
            } finally {
                database.close()
            }

            AndroidSQLiteDriver().open(dbFile.path).run {
                assertEquals(
                    listOf(USER to "text", NEW_USER to "text"),
                    rows("SELECT id, typeof(id) FROM users ORDER BY id"),
                )
                assertEquals(
                    listOf(EPISODE to "text", NEW_ITEM to "text"),
                    rows("SELECT itemId, typeof(itemId) FROM pending_downloads ORDER BY itemId"),
                )
                close()
            }
        }

    /**
     * CE (#902): server and media source ids became value classes over the text the server sent,
     * bound by Room as that text like the item ids (#773). A schema 21 database with a Jellyfin
     * server id (dashless) and source ids in both forms the server sends reads back as the same
     * ids, and ids the DAO writes are stored as the same text, never normalised.
     */
    @Test
    fun `server and source ids of a schema 21 database read and write as the text stored`() =
        runBlocking {
            helper.createDatabase(21).apply {
                insertRows(21)
                execSQL(
                    "INSERT INTO servers (id, name, currentServerAddressId, currentUserId) VALUES ('$JELLYFIN_SERVER', 'Jellyfin', NULL, NULL)"
                )
                execSQL(
                    "INSERT INTO movies (id, serverId, name, overview, runtimeTicks, status) VALUES ('$SECOND_MOVIE', '$JELLYFIN_SERVER', 'Other movie', '', 72000000000, 'Released')"
                )
                execSQL(
                    "INSERT INTO sources (id, itemId, name, type, path) VALUES ('$DASHLESS_SOURCE', '$SECOND_MOVIE', 'Own', 'LOCAL', '$PATH.own')"
                )
                execSQL(
                    "INSERT INTO sources (id, itemId, name, type, path) VALUES ('$DASHED_SOURCE', '$SECOND_MOVIE', 'Other', 'LOCAL', '$PATH.other')"
                )
                execSQL(
                    "INSERT INTO mediastreams (id, sourceId, title, language, type, codec, isExternal, path) VALUES ('$STREAM_ID', '$DASHLESS_SOURCE', 'English', 'eng', 'SUBTITLE', 'srt', 1, '$PATH.srt')"
                )
                execSQL(
                    "INSERT INTO trickplayInfos (sourceId, width, height, tileWidth, tileHeight, thumbnailCount, interval, bandwidth) VALUES ('$DASHED_SOURCE', 320, 180, 10, 10, 9, 10000, 892)"
                )
                close()
            }
            val database =
                Room.databaseBuilder(context, ServerDatabase::class.java, dbFile.path)
                    .setDriver(AndroidSQLiteDriver())
                    .addMigrations(*MIGRATIONS.toTypedArray())
                    .build()
            try {
                val dao = database.getServerDatabaseDao()
                val server = JELLYFIN_SERVER.toServerId()
                assertEquals(server, dao.getServer(server)!!.id)
                assertEquals(
                    SERVER.toServerId(),
                    dao.getUsers(SERVER.toServerId()).single().serverId,
                )
                assertEquals(server, dao.getMoviesByServerId(server).single().serverId)
                assertEquals(
                    listOf(DASHLESS_SOURCE.toMediaSourceId(), DASHED_SOURCE.toMediaSourceId()),
                    dao.getSources(SECOND_MOVIE.toItemId()).map { it.id }.sortedBy { it.asString() },
                )
                assertEquals(
                    DASHLESS_SOURCE.toMediaSourceId(),
                    dao.getMediaStreamsBySourceId(DASHLESS_SOURCE.toMediaSourceId())
                        .single()
                        .sourceId,
                )
                assertEquals(
                    DASHED_SOURCE.toMediaSourceId(),
                    dao.getTrickplayInfo(DASHED_SOURCE.toMediaSourceId())!!.sourceId,
                )
                // The other form of the same UUID is another id: nothing is normalised.
                assertNull(dao.getTrickplayInfo(DASHED_SOURCE.replace("-", "").toMediaSourceId()))

                dao.insertServer(
                    ServerEntity(
                        id = NEW_SERVER.toServerId(),
                        name = "New",
                        currentServerAddressId = null,
                        currentUserId = null,
                    )
                )
                dao.insertSource(
                    ItemSourceEntity(
                        id = NEW_SOURCE.toMediaSourceId(),
                        itemId = SECOND_MOVIE.toItemId(),
                        name = "New",
                        type = ItemSourceType.LOCAL,
                        path = "$PATH.new",
                    )
                )
            } finally {
                database.close()
            }

            AndroidSQLiteDriver().open(dbFile.path).run {
                assertEquals(
                    listOf(JELLYFIN_SERVER to "text", NEW_SERVER to "text", SERVER to "text"),
                    rows("SELECT id, typeof(id) FROM servers ORDER BY id"),
                )
                assertEquals(
                    listOf(
                        DASHLESS_SOURCE to "text",
                        DASHED_SOURCE to "text",
                        NEW_SOURCE to "text",
                    ),
                    rows(
                        "SELECT id, typeof(id) FROM sources WHERE itemId = '$SECOND_MOVIE' ORDER BY id"
                    ),
                )
                close()
            }
        }

    /**
     * CE (#918): server address and media stream ids became value classes over their text, without
     * the `UUID` converter that stored them before. A schema 21 database with the converter's text
     * (dashed lowercase) reads back as the same ids, queries by id find the row, and ids the DAO
     * writes, random ones too, are stored as the same text.
     */
    @Test
    fun `address and media stream ids of a schema 21 database read and write as the text stored`() =
        runBlocking {
            helper.createDatabase(21).apply {
                insertRows(21)
                execSQL(
                    "INSERT INTO mediastreams (id, sourceId, title, language, type, codec, isExternal, path) VALUES ('$STREAM_ID', 'source', 'English', 'eng', 'SUBTITLE', 'srt', 1, '$PATH.srt.download')"
                )
                close()
            }
            val randomAddress = ServerAddressId.random()
            val randomStream = MediaStreamId.random()
            val database =
                Room.databaseBuilder(context, ServerDatabase::class.java, dbFile.path)
                    .setDriver(AndroidSQLiteDriver())
                    .addMigrations(*MIGRATIONS.toTypedArray())
                    .build()
            try {
                val dao = database.getServerDatabaseDao()
                val server = dao.getServer(SERVER.toServerId())!!
                assertEquals(ADDRESS_ID.toServerAddressId(), server.currentServerAddressId)
                assertEquals(ADDRESS, dao.getAddress(ADDRESS_ID.toServerAddressId()).address)
                assertEquals(
                    ADDRESS_ID.toServerAddressId(),
                    dao.getServerCurrentAddress(SERVER.toServerId())!!.id,
                )
                assertTrue(
                    STREAM_ID.toMediaStreamId() in
                        dao.getMediaStreamsBySourceId("source".toMediaSourceId()).map { it.id }
                )
                dao.setMediaStreamPath(STREAM_ID.toMediaStreamId(), "$PATH.srt")

                dao.insertServerAddress(
                    ServerAddressEntity(NEW_ADDRESS_ID.toServerAddressId(), server.id, "http://a")
                )
                dao.insertServerAddress(ServerAddressEntity(randomAddress, server.id, "http://b"))
                dao.updateServer(server.copy(currentServerAddressId = randomAddress))
                dao.insertMediaStream(stream(NEW_STREAM_ID.toMediaStreamId()))
                dao.insertMediaStream(stream(randomStream))
                assertEquals(randomAddress, dao.getServerCurrentAddress(SERVER.toServerId())!!.id)
            } finally {
                database.close()
            }

            AndroidSQLiteDriver().open(dbFile.path).run {
                assertEquals(
                    listOf(ADDRESS_ID, NEW_ADDRESS_ID, randomAddress.asString()).sorted().map {
                        it to "text"
                    },
                    rows("SELECT id, typeof(id) FROM serverAddresses ORDER BY id"),
                )
                assertEquals(
                    randomAddress.asString(),
                    text("SELECT currentServerAddressId FROM servers WHERE id = '$SERVER'"),
                )
                assertEquals(
                    listOf(NEW_STREAM_ID, STREAM_ID, randomStream.asString(), "stream")
                        .sorted()
                        .map { it to "text" },
                    rows("SELECT id, typeof(id) FROM mediastreams ORDER BY id"),
                )
                assertEquals(
                    "$PATH.srt",
                    text("SELECT path FROM mediastreams WHERE id = '$STREAM_ID'"),
                )
                close()
            }
        }

    private fun stream(id: MediaStreamId) =
        MediaStreamEntity(
            id = id,
            sourceId = "source".toMediaSourceId(),
            title = "Deutsch",
            displayTitle = null,
            language = "deu",
            type = MediaStreamType.SUBTITLE,
            codec = "subrip",
            isExternal = true,
            path = "$PATH.$id.srt",
            channelLayout = null,
            videoRangeType = null,
            height = null,
            width = null,
            videoDoViTitle = null,
        )

    private fun migratesWithData(from: Int) = runBlocking {
        helper.createDatabase(from).apply {
            insertRows(from)
            close()
        }

        // Room's own check: the migrated tables match the exported schema exactly.
        helper.runMigrationsAndValidate(latest, MIGRATIONS).apply {
            assertRowsSurvived(from)
            close()
        }

        // The app's view: Room opens the file against the compiled entities (identity hash), with
        // the app's migrations and without the destructive fallback, and the DAO reads the rows.
        val database =
            Room.databaseBuilder(context, ServerDatabase::class.java, dbFile.path)
                .setDriver(AndroidSQLiteDriver())
                .addMigrations(*MIGRATIONS.toTypedArray())
                .build()
        try {
            val dao = database.getServerDatabaseDao()
            assertEquals(
                ServerType.JELLYFIN,
                dao.getServer(SERVER.toServerId())!!.toServer().serverType,
            )
            assertEquals(TOKEN, dao.getServerCurrentUser(SERVER.toServerId())!!.accessToken)
            assertEquals(ADDRESS, dao.getServerCurrentAddress(SERVER.toServerId())!!.address)
            assertEquals("Movie", dao.getMovie(MOVIE.toItemId()).name)
            assertEquals(PATH, dao.getSources(MOVIE.toItemId()).single().path)
            assertEquals(
                POSITION,
                dao.getUserData(MOVIE.toItemId(), USER.toUserId())!!.playbackPositionTicks,
            )
            assertEquals(EPISODE.toItemId(), dao.getPendingDownloads().single().itemId)
            assertEquals(300_000_000L, dao.getSegments(MOVIE.toItemId()).single().endTicks)
            if (from >= 14) {
                assertEquals(
                    "eng",
                    dao.getSeriesTrackPreferences(USER.toUserId(), SHOW.toItemId())
                        .single()
                        .language,
                )
            }
        } finally {
            database.close()
        }
    }

    /** Rows in the columns schema 12 has; later schemas only add columns with defaults. */
    private fun SQLiteConnection.insertRows(version: Int) {
        execSQL(
            "INSERT INTO servers (id, name, currentServerAddressId, currentUserId) VALUES ('$SERVER', 'Home', '$ADDRESS_ID', '$USER')"
        )
        execSQL(
            "INSERT INTO serverAddresses (id, serverId, address) VALUES ('$ADDRESS_ID', '$SERVER', '$ADDRESS')"
        )
        execSQL(
            "INSERT INTO users (id, name, serverId, accessToken) VALUES ('$USER', 'Alice', '$SERVER', '$TOKEN')"
        )
        execSQL(
            "INSERT INTO movies (id, serverId, name, overview, runtimeTicks, status) VALUES ('$MOVIE', '$SERVER', 'Movie', '', 72000000000, 'Released')"
        )
        execSQL(
            "INSERT INTO shows (id, serverId, name, overview, runtimeTicks, status) VALUES ('$SHOW', '$SERVER', 'Show', '', 0, 'Continuing')"
        )
        execSQL(
            "INSERT INTO seasons (id, seriesId, name, seriesName, overview, indexNumber) VALUES ('$SEASON', '$SHOW', 'Season 1', 'Show', '', 1)"
        )
        execSQL(
            "INSERT INTO episodes (id, serverId, seasonId, seriesId, name, seriesName, overview, indexNumber, parentIndexNumber, runtimeTicks) VALUES ('$EPISODE', '$SERVER', '$SEASON', '$SHOW', 'Episode 1', 'Show', '', 1, 1, 13000000000)"
        )
        execSQL(
            "INSERT INTO sources (id, itemId, name, type, path, downloadId, downloadMaxBitrate) VALUES ('source', '$MOVIE', 'Movie', 'LOCAL', '$PATH', 42, 8000000)"
        )
        execSQL(
            "INSERT INTO mediastreams (id, sourceId, title, language, type, codec, isExternal, path) VALUES ('stream', 'source', 'English', 'eng', 'AUDIO', 'aac', 0, '$PATH')"
        )
        execSQL(
            "INSERT INTO userdata (userId, itemId, played, favorite, playbackPositionTicks, toBeSynced) VALUES ('$USER', '$MOVIE', 0, 1, $POSITION, 1)"
        )
        execSQL(
            "INSERT INTO pending_downloads (itemId, itemKind, addedAt) VALUES ('$EPISODE', 'Episode', 1700000000)"
        )
        execSQL(
            "INSERT INTO segments (itemId, type, startTicks, endTicks) VALUES ('$MOVIE', 'INTRO', 0, 300000000)"
        )
        // Left behind by a deleted download, which #693 (schema 20) cleans up and prevents.
        if (version < 20) {
            execSQL(
                "INSERT INTO segments (itemId, type, startTicks, endTicks) VALUES ('$EPISODE', 'OUTRO', 0, 300000000)"
            )
        }
        if (version >= 14) {
            execSQL(
                "INSERT INTO seriesTrackPreferences (userId, seriesId, trackType, disabled, language, title, positionInLanguage) VALUES ('$USER', '$SHOW', 1, 0, 'eng', NULL, 0)"
            )
        }
        if (version >= 17) {
            execSQL(
                "UPDATE users SET pinHash = 'hash', pinSalt = 'salt', pinOnStart = 0 WHERE id = '$USER'"
            )
        }
        if (version >= 18) {
            execSQL("UPDATE users SET lockType = 'pattern' WHERE id = '$USER'")
        }
        if (version >= 19) {
            execSQL("UPDATE userdata SET lastPlayedDate = $LAST_PLAYED WHERE userId = '$USER'")
        }
    }

    private fun SQLiteConnection.assertRowsSurvived(from: Int) {
        // CE (#770): the one server is a Jellyfin server.
        assertEquals("jellyfin", text("SELECT type FROM servers WHERE id = '$SERVER'"))
        assertEquals(USER, text("SELECT currentUserId FROM servers WHERE id = '$SERVER'"))
        assertEquals(ADDRESS, text("SELECT address FROM serverAddresses WHERE id = '$ADDRESS_ID'"))
        assertEquals(TOKEN, text("SELECT accessToken FROM users WHERE id = '$USER'"))
        assertEquals(1L, long("SELECT COUNT(*) FROM episodes WHERE seasonId = '$SEASON'"))
        assertEquals(PATH, text("SELECT path FROM sources WHERE itemId = '$MOVIE'"))
        assertEquals(42L, long("SELECT downloadId FROM sources WHERE itemId = '$MOVIE'"))
        assertEquals(1L, long("SELECT COUNT(*) FROM mediastreams WHERE sourceId = 'source'"))
        assertEquals(POSITION, long("SELECT playbackPositionTicks FROM userdata"))
        assertEquals(1L, long("SELECT toBeSynced FROM userdata"))
        // CE (#633): progress waiting to be sent gets the time of the upgrade as its last play.
        val lastPlayed = long("SELECT lastPlayedDate FROM userdata")
        if (from >= 19) {
            assertEquals(LAST_PLAYED, lastPlayed)
        } else {
            assertTrue(
                abs(System.currentTimeMillis() - lastPlayed) < 60_000L,
                message = "lastPlayedDate $lastPlayed is not about now",
            )
        }
        assertEquals(EPISODE, text("SELECT itemId FROM pending_downloads"))
        assertEquals(0L, long("SELECT smartQueued FROM sources"))
        assertEquals(300_000_000L, long("SELECT endTicks FROM segments WHERE itemId = '$MOVIE'"))
        assertEquals(0L, long("SELECT COUNT(*) FROM segments WHERE itemId = '$EPISODE'"))
        if (from >= 14) {
            assertEquals("eng", text("SELECT language FROM seriesTrackPreferences"))
        }
        if (from >= 17) {
            assertEquals("hash", text("SELECT pinHash FROM users"))
            assertEquals(0L, long("SELECT pinOnStart FROM users"))
        } else {
            // A user from before profile locks has none, and asks for one at start once set.
            assertNull(text("SELECT pinHash FROM users"))
            assertEquals(1L, long("SELECT pinOnStart FROM users"))
        }
        assertEquals(if (from >= 18) "pattern" else "pin", text("SELECT lockType FROM users"))
    }

    /** CE (#770): a second server with its own address, user, download and offline position. */
    private fun SQLiteConnection.insertSecondServer() {
        execSQL(
            "INSERT INTO servers (id, name, currentServerAddressId, currentUserId) VALUES ('$SECOND_SERVER', 'Cabin', '$SECOND_ADDRESS_ID', '$SECOND_USER')"
        )
        execSQL(
            "INSERT INTO serverAddresses (id, serverId, address) VALUES ('$SECOND_ADDRESS_ID', '$SECOND_SERVER', 'http://192.168.1.20:8096')"
        )
        execSQL(
            "INSERT INTO users (id, name, serverId, accessToken) VALUES ('$SECOND_USER', 'Bob', '$SECOND_SERVER', '$SECOND_TOKEN')"
        )
        execSQL(
            "INSERT INTO movies (id, serverId, name, overview, runtimeTicks, status) VALUES ('$SECOND_MOVIE', '$SECOND_SERVER', 'Other movie', '', 72000000000, 'Released')"
        )
        execSQL(
            "INSERT INTO sources (id, itemId, name, type, path, downloadId, downloadMaxBitrate) VALUES ('second-source', '$SECOND_MOVIE', 'Other movie', 'LOCAL', '$PATH.2', 43, 8000000)"
        )
        execSQL(
            "INSERT INTO userdata (userId, itemId, played, favorite, playbackPositionTicks, toBeSynced) VALUES ('$SECOND_USER', '$SECOND_MOVIE', 1, 0, 0, 1)"
        )
    }

    /**
     * CE (#770): every table's columns (name, type, not null, default, key), foreign keys and
     * indexes, as SQLite reports them, one line each.
     */
    private fun SQLiteConnection.describeSchema(): Set<String> {
        val tables =
            rowsOf(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata')",
                    listOf(0),
                )
                .map { it.single()!! }
        return tables
            .flatMap { table ->
                val columns =
                    rowsOf("PRAGMA table_info(`$table`)", listOf(1, 2, 3, 4, 5)).map {
                        "$table column ${it.joinToString(" ")}"
                    }
                val foreignKeys =
                    rowsOf("PRAGMA foreign_key_list(`$table`)", listOf(2, 3, 4, 5, 6)).map {
                        "$table foreign key ${it.joinToString(" ")}"
                    }
                val indexes =
                    rowsOf("PRAGMA index_list(`$table`)", listOf(1, 2, 3)).map { index ->
                        val indexColumns =
                            rowsOf("PRAGMA index_info(`${index[0]}`)", listOf(2)).joinToString(
                                ","
                            ) {
                                it.single().toString()
                            }
                        "$table index ${index.joinToString(" ")} ($indexColumns)"
                    }
                columns + foreignKeys + indexes
            }
            .toSet()
    }

    /** The given columns of every row of [sql], as text (null stays null). */
    private fun SQLiteConnection.rowsOf(sql: String, columns: List<Int>): List<List<String?>> =
        prepare(sql).use { statement ->
            buildList {
                while (statement.step()) {
                    add(columns.map { if (statement.isNull(it)) null else statement.getText(it) })
                }
            }
        }

    /** The first two columns of every row of [sql]. */
    private fun SQLiteConnection.rows(sql: String): List<Pair<String?, String?>> =
        rowsOf(sql, listOf(0, 1)).map { it[0] to it[1] }

    private fun SQLiteConnection.text(sql: String): String? =
        prepare(sql).use {
            it.step()
            if (it.isNull(0)) null else it.getText(0)
        }

    private fun SQLiteConnection.long(sql: String): Long =
        prepare(sql).use {
            it.step()
            it.getLong(0)
        }

    private fun helper(file: java.io.File) =
        MigrationTestHelper(
            instrumentation = instrumentation,
            file = file,
            driver = AndroidSQLiteDriver(),
            databaseClass = ServerDatabase::class,
        )

    private companion object {
        const val SCHEMA_DIR = "dev.jdtech.jellyfin.database.ServerDatabase"

        /** The hand-written migrations `DatabaseModule` adds; the rest are auto-migrations. */
        val MIGRATIONS = listOf(MIGRATION_6_7)

        const val SERVER = "server"
        const val ADDRESS = "https://jellyfin.example"
        const val TOKEN = "access-token"
        const val PATH = "/storage/emulated/0/Android/data/downloads/movie.mkv"
        const val POSITION = 36_000_000_000L
        const val LAST_PLAYED = 1_700_000_000_000L
        const val ADDRESS_ID = "00000000-0000-0000-0000-00000000000a"
        const val USER = "00000000-0000-0000-0000-000000000001"
        const val MOVIE = "00000000-0000-0000-0000-000000000002"
        const val SHOW = "00000000-0000-0000-0000-000000000003"
        const val SEASON = "00000000-0000-0000-0000-000000000004"
        const val EPISODE = "00000000-0000-0000-0000-000000000005"

        const val SECOND_SERVER = "server-2"
        const val SECOND_TOKEN = "access-token-2"
        const val SECOND_ADDRESS_ID = "00000000-0000-0000-0000-00000000000b"
        const val SECOND_USER = "00000000-0000-0000-0000-000000000011"
        const val SECOND_MOVIE = "00000000-0000-0000-0000-000000000012"

        /** Ids the DAO writes in the #773 test, as `UUID.toString()` gave them. */
        const val NEW_USER = "0f8fad5b-d9cb-469f-a165-70867728950e"
        const val NEW_ITEM = "7c9e6679-7425-40de-944b-e07fc1f90ae7"

        /**
         * Ids of the #902 test, as Jellyfin sends them: a dashless server id, both source forms.
         */
        const val JELLYFIN_SERVER = "4b2d1e0ca8f04f5c9b7e3a1d2c6f8e90"
        const val NEW_SERVER = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        const val DASHLESS_SOURCE = "00000000000000000000000000000012"
        const val DASHED_SOURCE = "3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c"
        const val NEW_SOURCE = "9e8d7c6b5a4f43e2a1b0c9d8e7f6a5b4"
        const val STREAM_ID = "00000000-0000-0000-0000-0000000000c1"

        /** Ids the DAO writes in the #918 test, as `UUID.toString()` gave them. */
        const val NEW_ADDRESS_ID = "6f048d8b-aab4-4c97-9b05-8e7de4e6d604"
        const val NEW_STREAM_ID = "b3a1c2d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

        /** The one line [describeSchema] gains from #770. */
        const val SERVER_TYPE_COLUMN = "servers column type TEXT 1 'jellyfin' 0"
    }
}
