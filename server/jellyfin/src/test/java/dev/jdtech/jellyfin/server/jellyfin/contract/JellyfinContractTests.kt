package dev.jdtech.jellyfin.server.jellyfin.contract

import android.app.Application
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.CatalogPort
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.ServerConnector
import dev.jdtech.jellyfin.player.DeviceCodecProbe
import dev.jdtech.jellyfin.repository.StandInServer
import dev.jdtech.jellyfin.server.jellyfin.JellyfinDeviceProfiles
import dev.jdtech.jellyfin.server.jellyfin.JellyfinMediaServer
import dev.jdtech.jellyfin.server.jellyfin.JellyfinServerConnector
import dev.jdtech.jellyfin.testing.CatalogContract
import dev.jdtech.jellyfin.testing.ContractLibrary
import dev.jdtech.jellyfin.testing.ContractLibrary.Part
import dev.jdtech.jellyfin.testing.PlaybackContract
import dev.jdtech.jellyfin.testing.ServerConnectorContract
import dev.jdtech.jellyfin.testing.UserDataContract
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// #771: the Jellyfin adapter passes the contract suites of `testing`, against the recordings of
// the Docker test server, or against that server itself with EPHYRA_TEST_SERVER (see
// ContractServer). FakeMediaServerContractTest in `testing` runs the same suites on the fake.

/** The adapter's ports over [baseUrl], signed in as the library's user with the token `token`. */
private fun jellyfinAt(baseUrl: String): MediaServer {
    val context = StandInServer.context()
    val api =
        JellyfinApi(context).apply {
            api.update(baseUrl = baseUrl, accessToken = LiveServer.FAKE_TOKEN)
            userId = ContractLibrary.userId
        }
    return JellyfinMediaServer(api, JellyfinDeviceProfiles(DeviceCodecProbe(context)))
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JellyfinCatalogContractTest : CatalogContract() {
    @get:Rule val contract = ContractServer()

    /** Answers every request with HTTP 500. */
    private val failing = StandInServer(concurrent = true) { StandInServer.Response(500) }

    override val notOnServer: Set<Part>
        get() = contract.notOnServer

    override fun catalog(): CatalogPort = jellyfinAt(contract.baseUrl).catalog

    override fun failingCatalog(): CatalogPort = jellyfinAt(failing.baseUrl).catalog

    @After
    fun stop() {
        failing.close()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JellyfinUserDataContractTest : UserDataContract() {
    @get:Rule val contract = ContractServer()

    override fun server(): MediaServer = jellyfinAt(contract.baseUrl)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JellyfinPlaybackContractTest : PlaybackContract() {
    @get:Rule val contract = ContractServer()

    override val notOnServer: Set<Part>
        get() = contract.notOnServer

    override fun server(): MediaServer = jellyfinAt(contract.baseUrl)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JellyfinServerConnectorContractTest : ServerConnectorContract() {
    @get:Rule val contract = ContractServer()

    override fun connector(): ServerConnector =
        JellyfinServerConnector(JellyfinApi(StandInServer.context()))

    override val address: String
        get() = contract.baseUrl
}
