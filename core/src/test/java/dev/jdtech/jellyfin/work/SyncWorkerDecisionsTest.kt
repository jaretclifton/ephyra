package dev.jdtech.jellyfin.work

import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.UserDataEntity
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toUserId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** CE (#552): when the user data sync retries, and when it lets a row be. */
class SyncWorkerDecisionsTest {
    private val userId = "00000000-0000-0000-0000-000000000001".toUserId()

    private fun row(item: Int, positionTicks: Long = 100L) =
        UserDataEntity(
            userId = userId,
            itemId = UUID(0L, item.toLong()).toString().toItemId(),
            played = false,
            favorite = false,
            playbackPositionTicks = positionTicks,
            toBeSynced = true,
        )

    @Test
    fun `an item the server does not have is dropped`() {
        assertEquals(AfterStatus.DROP, afterStatus(404))
    }

    @Test
    fun `a refusal is not retried on a timer`() {
        for (status in listOf(400, 401, 403)) assertEquals(AfterStatus.LEAVE, afterStatus(status))
    }

    @Test
    fun `a server error is retried`() {
        for (status in listOf(500, 502, 503)) assertEquals(AfterStatus.RETRY, afterStatus(status))
    }

    @Test
    fun `a session the server still has playing is ended before its row is sent`() {
        val item = UUID(0L, 1L).toString().toItemId()
        assertTrue(
            endsStaleSession(rowItemId = item, leftPlayingOnServer = item, playingHere = null)
        )
        assertTrue(
            endsStaleSession(
                item,
                leftPlayingOnServer = item,
                playingHere = UUID(0L, 2L).toString().toItemId(),
            )
        )
    }

    @Test
    fun `no session, or one of another item, is left alone`() {
        val item = UUID(0L, 1L).toString().toItemId()
        assertFalse(endsStaleSession(item, leftPlayingOnServer = null, playingHere = null))
        assertFalse(
            endsStaleSession(
                item,
                leftPlayingOnServer = UUID(0L, 2L).toString().toItemId(),
                playingHere = null,
            )
        )
    }

    @Test
    fun `an item that is open in the player here keeps its session`() {
        val item = UUID(0L, 1L).toString().toItemId()
        assertFalse(endsStaleSession(item, leftPlayingOnServer = item, playingHere = item))
    }

    @Test
    fun `nothing left marked needs no other run`() {
        assertFalse(needsAnotherRun(sent = listOf(row(1)), stillMarked = emptyList()))
    }

    @Test
    fun `a row that failed as it was sent does not ask for another run by itself`() {
        assertFalse(needsAnotherRun(sent = listOf(row(1), row(2)), stillMarked = listOf(row(2))))
    }

    @Test
    fun `a row that changed during the run needs another run`() {
        assertTrue(needsAnotherRun(sent = listOf(row(1)), stillMarked = listOf(row(1, 200L))))
    }

    @Test
    fun `a row marked during the run needs another run`() {
        assertTrue(needsAnotherRun(sent = listOf(row(1)), stillMarked = listOf(row(2))))
    }

    @Test
    fun `a row marked during a run that only refreshed needs another run`() {
        // CE (#635): the run had nothing to send; a request made meanwhile was dropped (KEEP).
        assertTrue(needsAnotherRun(sent = emptyList(), stillMarked = listOf(row(1))))
        assertFalse(needsAnotherRun(sent = emptyList(), stillMarked = emptyList()))
    }

    @Test
    fun `only a 401 is a refused login`() {
        // CE (#635): logged as one line instead of a stack trace on every run.
        assertTrue(isRefusedLogin(ServerException.HttpStatus(401)))
        assertFalse(isRefusedLogin(ServerException.HttpStatus(500)))
        assertFalse(isRefusedLogin(ServerException.HttpStatus(404)))
        assertFalse(isRefusedLogin(java.io.IOException("no route to host")))
        assertFalse(isRefusedLogin(ServerException.Timeout()))
    }

    @Test
    fun `a wrapped 401 is a refused login too`() {
        // #745: the SDK throws its own type. #762: the port translates it. #763: core no longer
        // sees the SDK; that the SDK's 401 reads as HttpStatus(401), also wrapped, is
        // SdkErrorsTest.
        assertTrue(isRefusedLogin(RuntimeException("wrap", ServerException.HttpStatus(401))))
    }
}
