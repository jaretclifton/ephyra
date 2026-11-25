package dev.jdtech.jellyfin.film.presentation.home

import android.content.SharedPreferences
import dev.jdtech.jellyfin.repository.CurrentAccount
import dev.jdtech.jellyfin.repository.ItemCatalog
import dev.jdtech.jellyfin.repository.ItemLists
import dev.jdtech.jellyfin.repository.StoredServers
import dev.jdtech.jellyfin.repository.StoredUsers
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.FakeRoles
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.io.IOException
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Regression test for the offline crash documented in BUGREPORT_ANALYSIS.md.
 *
 * Drives the REAL [HomeViewModel] against a repository that behaves as if the device is offline —
 * every call throws [IOException], exactly like the Jellyfin SDK does with no network.
 *
 * Asserts that [HomeViewModel.loadData] does NOT leak an uncaught exception: the offline failure
 * must be caught and surfaced in [HomeState.error] for the UI to show. Before the `coroutineScope {
 * }` fix this test failed — `loadData()` crashed the app on startup.
 * [LoadDataConcurrencyMechanismTest] proves *why*.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelOfflineCrashTest {

    private var originalUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    /** #955: Main and the load on one test scheduler, so the load ends before the test does. */
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        // viewModelScope is built with Dispatchers.Main.immediate; provide one.
        Dispatchers.setMain(dispatcher)
        originalUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @AfterEach
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(originalUncaughtHandler)
        Dispatchers.resetMain()
    }

    @Test
    fun `loadData surfaces the offline failure in state without crashing`() =
        runTest(dispatcher) {
            // An uncaught coroutine exception with no CoroutineExceptionHandler in
            // context ends up here; on a real device this handler kills the process.
            // (runTest also fails on an exception that escapes a coroutine of its scheduler.)
            val uncaught = CompletableDeferred<Throwable>()
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.complete(e) }

            val viewModel =
                HomeViewModel(
                    lists = offlineRepository.role(),
                    catalog = offlineRepository.role(),
                    account = offlineRepository.role(),
                    appPreferences = AppPreferences(defaultsOnlySharedPreferences()),
                    storedServers = unusedDao.role(),
                    storedUsers = unusedDao.role(),
                    loadDispatcher = dispatcher,
                )

            val load = viewModel.loadData()
            advanceUntilIdle()
            val state = viewModel.state.value

            // The fix: nothing escapes -> the app does not crash.
            assertFalse(
                uncaught.isCompleted,
                message =
                    "HomeViewModel.loadData() must not leak an uncaught exception while offline",
            )

            // The offline failure is surfaced in HomeState instead, for the UI to show.
            assertTrue(load.isCompleted, message = "loadData() should have settled")
            assertFalse(state.isLoading, message = "loadData() should finish loading")
            assertNotNull(
                state.error,
                message = "loadData() should surface the offline error in HomeState",
            )
            assertTrue(
                generateSequence<Throwable>(state.error) { it.cause }.any { it is IOException },
                message = "the surfaced error should be the offline IOException",
            )
        }

    /**
     * Every repository call throws [IOException] — models a device with no network, which is how
     * the Jellyfin SDK fails. [HomeViewModel] only calls a handful of these (getSuggestions /
     * getResumeItems / getNextUp / getUserViews); the rest are never reached.
     */
    private val offlineRepository: FakeRoles =
        FakeRoles(ItemLists::class, ItemCatalog::class, CurrentAccount::class) { proxy, method, args
            ->
            when {
                method.declaringClass == Any::class.java ->
                    when (method.kotlinName) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> "offlineRepository"
                        "equals" -> proxy === args?.getOrNull(0)
                        else -> null
                    }
                else -> throw IOException("Simulated offline: ${method.kotlinName}")
            }
        }

    /**
     * Not used by `loadData()` when there is no current server (see
     * [defaultsOnlySharedPreferences]); it only has to exist for the constructor.
     */
    private val unusedDao: FakeRoles =
        FakeRoles(StoredServers::class, StoredUsers::class) { _, method, _ ->
            throw NotImplementedError(
                "${method.declaringClass.simpleName}.${method.kotlinName} not expected in this test"
            )
        }

    /**
     * A [SharedPreferences] whose getters return the supplied default value. `AppPreferences` then
     * reports its own declared defaults: `currentServer` = null (so `loadData()` skips the database
     * entirely) and the `home*` toggles = true (so every section actually calls the repository).
     */
    private fun defaultsOnlySharedPreferences(): SharedPreferences =
        fakeProxy(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { proxy, method, args ->
            when (method.kotlinName) {
                // AppPreferences.getValue always passes the default as the 2nd arg.
                "getString",
                "getBoolean",
                "getInt",
                "getLong",
                "getFloat",
                "getStringSet" -> args!![1]
                "contains" -> false
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "defaultsOnlySharedPreferences"
                "equals" -> proxy === args?.getOrNull(0)
                else ->
                    throw UnsupportedOperationException("SharedPreferences.${method.kotlinName}")
            }
        }
            as SharedPreferences
}
