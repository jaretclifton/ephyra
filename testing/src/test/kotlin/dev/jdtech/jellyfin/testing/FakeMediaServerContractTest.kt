package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.domain.ports.CatalogPort
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.ServerConnector
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerException

// #771: the fake passes every suite, as the Jellyfin adapter does (`Jellyfin*ContractTest` in
// server/jellyfin). The suites are what keep the fake honest.

class FakeMediaServerContractTest : CatalogContract() {
    override fun catalog(): CatalogPort = FakeMediaServer().catalog

    override fun failingCatalog(): CatalogPort =
        FakeMediaServer().apply { failWith(ServerException.HttpStatus(500)) }.catalog
}

/** A server type without the optional features: the capability-gated calls give nothing. */
class FakeMediaServerWithoutFeaturesContractTest : CatalogContract() {
    override val capabilities = NONE

    override fun catalog(): CatalogPort = FakeMediaServer(capabilities = NONE).catalog

    override fun failingCatalog(): CatalogPort =
        FakeMediaServer(capabilities = NONE)
            .apply { failWith(ServerException.HttpStatus(500)) }
            .catalog
}

class FakeMediaServerUserDataContractTest : UserDataContract() {
    override fun server(): MediaServer = FakeMediaServer()
}

class FakeMediaServerPlaybackContractTest : PlaybackContract() {
    override fun server(): MediaServer = FakeMediaServer()
}

class FakeServerConnectorContractTest : ServerConnectorContract() {
    override fun connector(): ServerConnector = FakeServerConnector()

    override val address = ContractLibrary.address
}

private val NONE =
    ServerCapabilities(
        quickConnect = false,
        publicUsers = false,
        loginDisclaimer = false,
        mediaSegments = false,
        trickplay = false,
        themeSongs = false,
        similarItems = false,
        extras = false,
        itemCollections = false,
        serverResumeThresholds = false,
        autoPlayNextSetting = false,
        sessionSocket = false,
        deviceRename = false,
        cappedDownloadTranscode = false,
    )
