package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.domain.ports.AuthenticatedUser
import dev.jdtech.jellyfin.domain.ports.ResolvedServer
import dev.jdtech.jellyfin.domain.ports.ServerConnectException
import dev.jdtech.jellyfin.domain.ports.ServerIssue
import dev.jdtech.jellyfin.models.DiscoveredServer
import dev.jdtech.jellyfin.models.QuickConnectState
import dev.jdtech.jellyfin.models.ServerInfo
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.discovery.RecommendedServerIssue
import org.jellyfin.sdk.model.ServerVersion
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import org.jellyfin.sdk.model.api.UserDto
import org.junit.jupiter.api.Test

/**
 * #759: the onboarding screens get our own types, mapped from the SDK's. #761: the mappings moved
 * with the SDK calls from the setup repository to [JellyfinServerConnector].
 */
class JellyfinServerConnectorMappingTest {
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

    private fun recommended(systemInfo: Result<PublicSystemInfo>) =
        RecommendedServerInfo(
            address = "https://jf.example",
            responseTime = 10,
            score = RecommendedServerInfoScore.GREAT,
            issues = emptyList(),
            systemInfo = systemInfo,
        )

    @Test
    fun `a found server keeps its id, name, version, product and address`() {
        val info =
            PublicSystemInfo(
                id = "abc123",
                serverName = "Living room",
                version = "10.11.0",
                productName = "Jellyfin Server",
            )

        assertEquals(
            ResolvedServer(
                info =
                    ServerInfo(
                        id = "abc123",
                        name = "Living room",
                        version = "10.11.0",
                        product = "Jellyfin Server",
                    ),
                address = "https://jf.example",
            ),
            recommended(Result.success(info)).toResolvedServer(),
        )
    }

    @Test
    fun `a found server without an id, a name or any info is refused as having no id`() {
        val noId = listOf(ServerIssue(ServerIssue.Kind.NO_ID))
        for (systemInfo in
            listOf(
                Result.success(PublicSystemInfo(id = null, serverName = "Living room")),
                Result.success(PublicSystemInfo(id = "abc123", serverName = null)),
                Result.failure(IllegalStateException("no info")),
            )) {
            val error =
                assertFailsWith<ServerConnectException> {
                    recommended(systemInfo).toResolvedServer()
                }
            assertEquals(noId, error.issues)
        }
    }

    @Test
    fun `each issue keeps what its message shows`() {
        val version = ServerVersion.fromString("10.7.0")!!
        assertEquals(
            ServerIssue(ServerIssue.Kind.OUTDATED_VERSION, version.toString()),
            RecommendedServerIssue.OutdatedServerVersion(version).toServerIssue(),
        )
        assertEquals(
            ServerIssue(ServerIssue.Kind.UNSUPPORTED_VERSION, version.toString()),
            RecommendedServerIssue.UnsupportedServerVersion(version).toServerIssue(),
        )
        assertEquals(
            ServerIssue(ServerIssue.Kind.NOT_THIS_SERVER_TYPE, "Emby Server"),
            RecommendedServerIssue.InvalidProductName("Emby Server").toServerIssue(),
        )
        assertEquals(
            ServerIssue(ServerIssue.Kind.NOT_THIS_SERVER_TYPE, ""),
            RecommendedServerIssue.InvalidProductName(null).toServerIssue(),
        )
        assertEquals(
            ServerIssue(ServerIssue.Kind.SLOW_RESPONSE, "4200"),
            RecommendedServerIssue.SlowResponse(4200).toServerIssue(),
        )
        assertEquals(
            ServerIssue(ServerIssue.Kind.UNKNOWN),
            RecommendedServerIssue.MissingVersion.toServerIssue(),
        )
    }

    @Test
    fun `a login result keeps the user, the server and the token`() {
        val id = UUID.randomUUID()
        val result =
            AuthenticationResult(
                user =
                    UserDto(
                        id = id,
                        name = "alice",
                        primaryImageTag = "tag",
                        hasPassword = true,
                        hasConfiguredPassword = true,
                        hasConfiguredEasyPassword = false,
                    ),
                accessToken = "token",
                serverId = "abc123",
            )

        assertEquals(
            AuthenticatedUser(
                id = id.toUserId(),
                name = "alice",
                serverId = "abc123",
                accessToken = "token",
                primaryImageTag = "tag",
            ),
            result.toAuthenticatedUser(),
        )
    }
}
