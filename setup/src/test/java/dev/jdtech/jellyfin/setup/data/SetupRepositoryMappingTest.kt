package dev.jdtech.jellyfin.setup.data

import dev.jdtech.jellyfin.models.DiscoveredServer
import dev.jdtech.jellyfin.models.QuickConnectState
import java.time.LocalDateTime
import kotlin.test.assertEquals
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import org.junit.jupiter.api.Test

/** #759: the repository hands the onboarding screens our own types, mapped from the SDK's. */
class SetupRepositoryMappingTest {
    @Test
    fun `a discovered server keeps its id, name and address`() {
        val info =
            ServerDiscoveryInfo(
                address = "http://192.168.1.10:8096",
                id = "abc123",
                name = "Living room",
                endpointAddress = "192.168.1.10",
            )

        assertEquals(
            DiscoveredServer(
                id = "abc123",
                name = "Living room",
                address = "http://192.168.1.10:8096",
            ),
            info.toDiscoveredServer(),
        )
    }

    @Test
    fun `a quick connect result keeps its code, secret and approval`() {
        fun result(authenticated: Boolean) =
            QuickConnectResult(
                authenticated = authenticated,
                secret = "secret",
                code = "123456",
                deviceId = "device",
                deviceName = "device",
                appName = "Ephyra",
                appVersion = "test",
                dateAdded = LocalDateTime.now(),
            )

        assertEquals(
            QuickConnectState(code = "123456", secret = "secret", authenticated = false),
            result(authenticated = false).toQuickConnectState(),
        )
        assertEquals(
            QuickConnectState(code = "123456", secret = "secret", authenticated = true),
            result(authenticated = true).toQuickConnectState(),
        )
    }
}
