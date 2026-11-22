package dev.jdtech.jellyfin.core.server

import dev.jdtech.jellyfin.domain.ports.ResolvedServer
import dev.jdtech.jellyfin.domain.ports.ServerConnectException
import dev.jdtech.jellyfin.domain.ports.ServerConnector
import dev.jdtech.jellyfin.domain.ports.ServerIssue
import dev.jdtech.jellyfin.domain.ports.UnsupportedServerTypeException
import dev.jdtech.jellyfin.models.DiscoveredServer
import dev.jdtech.jellyfin.models.ServerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/**
 * #770: the [ServerConnector] of every server type this build has an adapter for. Adding a server
 * tries each of them for the address; everything after that uses the one of the server's type.
 */
class ServerConnectors(private val connectors: Map<ServerType, ServerConnector>) {
    /** In the order of [ServerType.entries], so which connector is tried first is fixed. */
    private val installed: List<Pair<ServerType, ServerConnector>> =
        ServerType.entries.mapNotNull { type -> connectors[type]?.let { type to it } }

    /** The connector for servers of [type]. Throws [UnsupportedServerTypeException] without one. */
    fun of(type: ServerType): ServerConnector =
        connectors[type] ?: throw UnsupportedServerTypeException(type.value)

    /**
     * The connector for a server whose stored type is [storedType] (see [forStoredType]), or null
     * when this build has none.
     */
    fun ofStoredTypeOrNull(storedType: String?): ServerConnector? =
        connectors.forStoredType(storedType)

    /** As [ofStoredTypeOrNull], but throws [UnsupportedServerTypeException] without a connector. */
    fun ofStoredType(storedType: String?): ServerConnector =
        ofStoredTypeOrNull(storedType)
            ?: throw UnsupportedServerTypeException(storedType ?: ServerType.JELLYFIN.value)

    /** The servers that answer the search on the local network, of every type. */
    fun discover(): Flow<DiscoveredServer> =
        installed.map { (_, connector) -> connector.discover() }.merge()

    /**
     * The type and the server at [address]: the first connector that recognizes the server wins. A
     * connector that finds another kind of server there ([ServerIssue.Kind.NOT_THIS_SERVER_TYPE])
     * or nothing at all passes to the next. Any other issue is the answer, as is any other failure:
     * it is the right kind of server, and it cannot be used.
     *
     * When no connector takes the server, throws the refusal of the first one that found a server,
     * else the first refusal. With one connector, that is its own [ServerConnectException].
     */
    suspend fun resolve(address: String): Pair<ServerType, ResolvedServer> {
        var refusal: ServerConnectException? = null
        for ((type, connector) in installed) {
            try {
                return type to connector.resolve(address)
            } catch (e: ServerConnectException) {
                val passes =
                    e.issues.isEmpty() ||
                        e.issues.any { it.kind == ServerIssue.Kind.NOT_THIS_SERVER_TYPE }
                if (!passes) throw e
                if (refusal == null || (refusal.issues.isEmpty() && e.issues.isNotEmpty())) {
                    refusal = e
                }
            }
        }
        throw refusal ?: ServerConnectException(emptyList())
    }
}
