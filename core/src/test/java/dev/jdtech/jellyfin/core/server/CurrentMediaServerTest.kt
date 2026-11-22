package dev.jdtech.jellyfin.core.server

import dev.jdtech.jellyfin.core.presentation.session.NoServerSession
import dev.jdtech.jellyfin.domain.ports.CatalogPort
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.PlaybackPort
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.domain.ports.UnsupportedServerTypeException
import dev.jdtech.jellyfin.domain.ports.UserDataPort
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerType
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** #770: [CurrentMediaServer] delegates to the adapter of the current server's type. */
class CurrentMediaServerTest {
    private val jellyfin = FakeMediaServer(ServerType.JELLYFIN, ServerCapabilities.ALL)
    private val emby =
        FakeMediaServer(ServerType.EMBY, ServerCapabilities.ALL.copy(quickConnect = false))
    private val currentType = CurrentServerType()

    @Test
    fun `every port is the current type's adapter's`() {
        val server =
            CurrentMediaServer(
                mapOf(ServerType.JELLYFIN to jellyfin, ServerType.EMBY to emby),
                currentType,
            )

        currentType.stored = "emby"

        assertEquals(ServerType.EMBY, server.type)
        assertSame(emby.capabilities, server.capabilities)
        assertSame(emby.session, server.session)
        assertSame(emby.catalog, server.catalog)
        assertSame(emby.userData, server.userData)
        assertSame(emby.playback, server.playback)
    }

    @Test
    fun `it follows a switch of server, and a call keeps the adapter it started on`() {
        val server =
            CurrentMediaServer(
                mapOf(ServerType.JELLYFIN to jellyfin, ServerType.EMBY to emby),
                currentType,
            )
        currentType.stored = "jellyfin"
        val startedOnJellyfin = server.catalog

        currentType.stored = "emby"

        assertSame(jellyfin.catalog, startedOnJellyfin)
        assertSame(emby.catalog, server.catalog)
        assertEquals(ServerType.EMBY, server.type)

        currentType.stored = "jellyfin"

        assertSame(jellyfin.catalog, server.catalog)
    }

    @Test
    fun `without a server it is the Jellyfin adapter, as before servers had a type`() {
        val server = CurrentMediaServer(mapOf(ServerType.JELLYFIN to jellyfin), currentType)

        assertNull(currentType.stored)
        assertSame(jellyfin.catalog, server.catalog)
        assertSame(jellyfin.session, server.session)
    }

    @Test
    fun `a type without an adapter offers nothing and fails every request`() = runBlocking {
        val server = CurrentMediaServer(mapOf(ServerType.JELLYFIN to jellyfin), currentType)

        for (stored in listOf("emby", "plex")) {
            currentType.stored = stored

            assertEquals(ServerCapabilities.NONE, server.capabilities)
            assertSame(NoServerSession, server.session)
            assertNull(server.playback.chapterImageUrl(ITEM, 0, "tag"))
            assertNull(server.playback.seasonBackdropUrl(ITEM))
            val failures =
                listOf(
                    assertFailsWith<UnsupportedServerTypeException> { server.catalog.movie(ITEM) },
                    assertFailsWith<UnsupportedServerTypeException> {
                        server.userData.setPlayed(ITEM, true)
                    },
                    assertFailsWith<UnsupportedServerTypeException> {
                        server.playback.segments(ITEM)
                    },
                    assertFailsWith<UnsupportedServerTypeException> { server.type },
                )
            failures.forEach { assertEquals(stored, it.storedType) }
        }
    }

    /** Ports that are only compared, never called. */
    private class FakeMediaServer(
        override val type: ServerType,
        override val capabilities: ServerCapabilities,
    ) : MediaServer {
        override val session: ServerSession = uncalled()
        override val catalog: CatalogPort = uncalled()
        override val userData: UserDataPort = uncalled()
        override val playback: PlaybackPort = uncalled()
    }

    private companion object {
        val ITEM: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

        inline fun <reified T> uncalled(): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) {
                proxy,
                method,
                args ->
                when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "uncalled ${T::class.simpleName}"
                    else -> throw NotImplementedError("${T::class.simpleName}.${method.name}")
                }
            } as T
    }
}
