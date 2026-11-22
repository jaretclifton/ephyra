package dev.jdtech.jellyfin.film.presentation.downloads

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadQueue
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSourceDto
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * #866: Downloads stays on its stack across a user switch and reloads in place on return. The same
 * downloads carry another user's played state and progress, and the previous user's stayed on
 * screen until that reload ended.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelUserSwitchTest {
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val movieId = UUID.randomUUID()

    /** One download, as each user sees it: Alice is halfway, Bob has not started. */
    private val aliceMovie =
        dummyMovie.copy(
            id = movieId,
            played = false,
            playbackPositionTicks = 50,
            runtimeTicks = 100,
        )
    private val bobMovie = aliceMovie.copy(playbackPositionTicks = 0)

    /** The user the client acts for. */
    @Volatile private var sessionUser: UUID = alice

    /** Holds Bob's read (on the IO thread) until the test has looked at the screen before it. */
    private val bobsRead = CountDownLatch(1)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the screen shows the new user's progress after a switch, never the previous user's`() =
        runBlocking {
            val viewModel = viewModel()
            // The screen's first ON_RESUME, which leaves the load to the ViewModel's own.
            viewModel.onScreenResumed()
            val first = withTimeout(5_000) { viewModel.state.first { it.sections.isNotEmpty() } }
            assertEquals(listOf<FindroidItem>(aliceMovie), items(first))

            // Home's profile sheet, a login or the Users screen: the client acts for Bob now.
            sessionUser = bob

            // The screen comes back (DownloadsScreen's ON_RESUME).
            viewModel.onScreenResumed()
            // Before Bob's user data is read: none of Alice's progress, and a loading list.
            assertEquals(emptyList(), items(viewModel.state.value))
            assertTrue(viewModel.state.value.isLoading)

            bobsRead.countDown()
            val after =
                withTimeout(5_000) {
                    viewModel.state.first { it.userId == bob && it.sections.isNotEmpty() }
                }
            assertEquals(listOf<FindroidItem>(bobMovie), items(after))
        }

    @Test
    fun `the screen shows nothing of another user's progress`() {
        val aliceState =
            DownloadsState(
                sections =
                    listOf(
                        CollectionSection(0, UiText.DynamicString("Movies"), listOf(aliceMovie))
                    ),
                storageUsedBytes = 7,
                userId = alice,
            )

        val forBob = aliceState.shownTo(bob)

        assertEquals(emptyList(), forBob.sections)
        assertTrue(forBob.isLoading)
        // The storage and the queue belong to no user.
        assertEquals(7, forBob.storageUsedBytes)
        assertEquals(aliceState, aliceState.shownTo(alice))
        // No session at hand (previews), or a list from before the user was known.
        assertEquals(aliceState, aliceState.shownTo(null))
        val unknown = aliceState.copy(userId = null)
        assertEquals(unknown, unknown.shownTo(bob))
    }

    private fun items(state: DownloadsState): List<FindroidItem> =
        state.sections.flatMap { it.items }

    private fun viewModel(): DownloadsViewModel {
        val repository = repository()
        val preferences = appPreferences()
        val database = database()
        val context = ContextWrapper(null) as Context
        return DownloadsViewModel(
            context = context,
            repository = repository,
            appPreferences = preferences,
            downloadQueue =
                DownloadQueue(
                    downloader = unused(),
                    appPreferences = preferences,
                    repositoryProvider = { repository },
                    database = database,
                    context = context,
                ),
            downloader = unused(),
            database = database,
        )
    }

    private fun repository(): JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getCapabilities" -> ServerCapabilities.ALL
                "getUserId" -> sessionUser
                // Room's copy of the downloads, with the user data of the user the client acts for.
                "getDownloads" ->
                    if (sessionUser == bob) {
                        bobsRead.await(5, TimeUnit.SECONDS)
                        listOf(bobMovie)
                    } else {
                        listOf(aliceMovie)
                    }
                else -> throw NotImplementedError("JellyfinRepository.${method.name}")
            }
        } as JellyfinRepository

    private fun database(): ServerDatabaseDao =
        Proxy.newProxyInstance(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getActiveDownloadSources",
                "getMediaStreamsForSources" -> emptyList<Any>()
                // A finished download of every item asked for.
                "getSourcesForItems" ->
                    (args!![0] as List<*>).map { id ->
                        FindroidSourceDto(
                            id = "source-$id",
                            itemId = id as UUID,
                            name = "source",
                            type = FindroidSourceType.LOCAL,
                            path = "/nowhere/$id.mkv",
                        )
                    }
                else -> throw NotImplementedError("ServerDatabaseDao.${method.name}")
            }
        } as ServerDatabaseDao

    private fun appPreferences(): AppPreferences {
        val preferences =
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getBoolean",
                    "getString",
                    "getInt",
                    "getLong",
                    "getFloat",
                    "getStringSet" -> args!![1]
                    else -> throw UnsupportedOperationException("SharedPreferences.${method.name}")
                }
            } as SharedPreferences
        return AppPreferences(preferences)
    }

    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw NotImplementedError("${T::class.java.simpleName}.${method.name}")
        } as T
}
