package dev.jdtech.jellyfin.player.local.domain

import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.UiText
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** CE (#825): a failed quality or audio switch tells the reason, not the exception's text. */
class SwitchFailureTextTest {
    @Test
    fun `a server error names its status`() {
        val text = switchFailureText(ServerException.HttpStatus(500))

        text as UiText.StringResource
        assertEquals(CoreR.string.ce_error_server_status, text.resId)
        assertEquals(listOf<Any>(500), text.args.toList())
    }

    @Test
    fun `an unknown error is a resource, not its own message`() {
        val text = switchFailureText(IOException("java.net.SocketTimeoutException: timeout"))

        assertTrue(text is UiText.StringResource)
    }
}
