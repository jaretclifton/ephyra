package dev.jdtech.jellyfin.models

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/** CE (#984): the server lists show the address the server uses, not the first one saved. */
class ServerCurrentAddressTest {
    private val id = "a".toServerId()
    private val lan = ServerAddress(ServerAddressId.random(), id, "http://10.0.2.2:8096")
    private val https = ServerAddress(ServerAddressId.random(), id, "https://a.example")

    private fun server(current: ServerAddressId?, vararg addresses: ServerAddress) =
        ServerWithAddresses(Server(id, "a", current, null), addresses.toList(), null)

    @Test
    fun `the current address is the one the server points at`() {
        assertEquals(https, server(https.id, lan, https).currentAddress)
    }

    @Test
    fun `without a current one it is the first, and none without addresses`() {
        assertEquals(lan, server(null, lan, https).currentAddress)
        assertEquals(lan, server(ServerAddressId.random(), lan, https).currentAddress)
        assertNull(server(https.id).currentAddress)
    }
}
