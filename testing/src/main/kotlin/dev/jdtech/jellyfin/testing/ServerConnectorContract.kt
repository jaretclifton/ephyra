package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.domain.ports.ServerConnector
import dev.jdtech.jellyfin.models.ServerException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The contract of [ServerConnector] (#771): finding the server of [ContractLibrary] and logging in
 * as [ContractLibrary.USER_NAME]. Quick Connect is tested only where the server type has it.
 */
abstract class ServerConnectorContract {
    abstract fun connector(): ServerConnector

    /** The address the server of [connector] answers at. */
    abstract val address: String

    private val library = ContractLibrary

    @Test
    fun `resolving an address gives the server's id and name`() = runTest {
        val resolved = connector().resolve(address)

        assertEquals(library.server.id, resolved.info.id)
        assertEquals(library.server.name, resolved.info.name)
        assertTrue(resolved.address.isNotBlank())
    }

    @Test
    fun `the right password logs in, and the session can be ended`() = runTest {
        val connector = connector()
        val user = connector.login(address, library.USER_NAME, library.PASSWORD)

        assertEquals(library.USER_NAME, user.name)
        assertEquals(library.server.id, user.serverId)
        assertTrue(user.accessToken.isNotBlank())
        assertTrue(connector.endSession(address, user.accessToken))
    }

    @Test
    fun `a wrong password is HTTP 401`() = runTest {
        val error =
            assertFailsWith<ServerException> {
                connector().login(address, library.USER_NAME, library.WRONG_PASSWORD)
            }

        assertEquals(401, assertIs<ServerException.HttpStatus>(error).status)
    }

    @Test
    fun `Quick Connect starts with a code that is not yet approved`() = runTest {
        val connector = connector()
        assumeTrue(connector.capabilities.quickConnect)
        assumeTrue("Quick Connect is off", connector.quickConnectEnabled(address))

        val started = connector.startQuickConnect(address)

        assertTrue(started.code.isNotBlank())
        assertTrue(started.secret.isNotBlank())
        assertFalse(started.authenticated)
        assertFalse(connector.quickConnectState(address, started.secret).authenticated)
    }
}
