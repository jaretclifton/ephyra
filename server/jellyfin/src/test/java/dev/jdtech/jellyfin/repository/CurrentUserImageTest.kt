package dev.jdtech.jellyfin.repository

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.models.ServerEntity
import dev.jdtech.jellyfin.models.UserEntity
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #757: the profile picture tag goes to the row of the user the server answered for, the one whose
 * token went out. During a switch of user the token is updated before the id, so the session's id
 * can be the previous user's while the request already carries the next one's token.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CurrentUserImageTest {
    private val server = StandInServer { request ->
        if (request.path == "/Users/Me") ANSWER else null
    }
    private val context = StandInServer.context()
    private val room =
        Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val database = room.getServerDatabaseDao()

    @After
    fun stop() {
        server.close()
        room.close()
    }

    @Test
    fun `the tag goes to the user the server answered for`() = runBlocking {
        database.insertServer(ServerEntity(SERVER.toServerId(), "Server", null, OTHER))
        database.insertUser(
            UserEntity(id = StandInServer.USER_ID, name = "Session", serverId = SERVER.toServerId())
        )
        database.insertUser(UserEntity(id = OTHER, name = "Token", serverId = SERVER.toServerId()))

        // The session's id is USER_ID; the server answers for OTHER, the token's user.
        server.repository(context, database).refreshCurrentUserImage()

        assertEquals("tag-of-other", database.getUser(OTHER)?.primaryImageTag)
        assertNull(database.getUser(StandInServer.USER_ID)?.primaryImageTag)
    }

    private companion object {
        const val SERVER = "server"
        val OTHER: UserId = "00000000-0000-0000-0000-000000000002".toUserId()
        const val ANSWER =
            "{\"Id\":\"00000000-0000-0000-0000-000000000002\",\"Name\":\"Token\"," +
                "\"PrimaryImageTag\":\"tag-of-other\"," +
                "\"HasPassword\":false,\"HasConfiguredPassword\":false," +
                "\"HasConfiguredEasyPassword\":false,\"EnableAutoLogin\":false}"
    }
}
