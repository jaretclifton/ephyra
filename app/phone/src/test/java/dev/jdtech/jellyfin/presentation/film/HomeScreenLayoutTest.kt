package dev.jdtech.jellyfin.presentation.film

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.width
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeSection
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovies
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServer
import dev.jdtech.jellyfin.core.presentation.dummy.dummyUser
import dev.jdtech.jellyfin.core.presentation.session.LocalServerSession
import dev.jdtech.jellyfin.core.presentation.session.NoServerSession
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.film.presentation.home.HomeState
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.HomeSection
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h914dp")
class HomeScreenLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var state by mutableStateOf(HomeState())

    private fun setLayout(initial: HomeState) {
        state = initial
        rule.setContent { FindroidTheme { HomeScreenLayout(state = state, onAction = {}) } }
        rule.mainClock.advanceTimeBy(1_000)
    }

    private fun string(id: Int) = rule.activity.getString(id)

    private val loaded =
        HomeState(
            server = dummyServer.copy(name = "Stable Demo"),
            resumeSection = dummyHomeSection,
            isLoading = false,
        )

    /** #391: a reload showed the pull indicator and a second spinner in the header. */
    @Test
    fun aReloadShowsOneLoadingIndicator() {
        setLayout(
            HomeState(server = dummyServer, resumeSection = dummyHomeSection, isLoading = true)
        )

        rule
            .onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertCountEquals(1)
    }

    /** #391: the first load after a cold start, with nothing on screen yet, still shows one. */
    @Test
    fun theFirstLoadShowsOneLoadingIndicator() {
        setLayout(HomeState(server = dummyServer, isLoading = true))

        rule
            .onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertCountEquals(1)
    }

    /**
     * #392: a failed refresh added an error and a retry button to the header, which squeezed the
     * server name to the logo and a dot. The header keeps its width; the error is a card instead.
     */
    @Test
    fun aFailedRefreshLeavesTheServerNameItsRoom() {
        setLayout(loaded)
        val nameWidth = rule.onNodeWithText("Stable Demo").getBoundsInRoot().width

        state = loaded.copy(error = Exception("Failed to connect to /10.0.2.2:8096"))
        rule.mainClock.advanceTimeBy(1_000)

        assertEquals(nameWidth, rule.onNodeWithText("Stable Demo").getBoundsInRoot().width)
        rule.onNodeWithText(string(CoreR.string.error_loading_data)).assertIsDisplayed()
    }

    /** #872: the client's session, whose user a switch changes without Compose seeing it. */
    private class SwitchableSession(var user: UserId?) : ServerSession by NoServerSession {
        override val userId: UserId?
            get() = user
    }

    private val alice = dummyUser.copy(id = UUID.randomUUID().toString().toUserId(), name = "Alice")
    private val bob = dummyUser.copy(id = UUID.randomUUID().toString().toUserId(), name = "Bob")
    private val aliceHome =
        HomeState(
            server = dummyServer,
            user = alice,
            resumeSection =
                HomeItem.Section(
                    HomeSection(
                        id = UUID.randomUUID().toString().toItemId(),
                        name = UiText.DynamicString("Alice's row"),
                        items = dummyMovies,
                    )
                ),
            isLoading = false,
        )

    /**
     * Home as HomeScreen wires it, on [aliceHome] (the ViewModel has not heard of a switch yet).
     * Returns what the profile sheet's switch calls.
     */
    private fun setHomeOfSession(session: ServerSession): () -> Unit {
        var recheck: () -> Unit = {}
        rule.setContent {
            FindroidTheme {
                CompositionLocalProvider(LocalServerSession provides session) {
                    val sessionUser = rememberSessionUser()
                    recheck = sessionUser::recheck
                    HomeScreenLayout(state = aliceHome.shownTo(sessionUser.userId), onAction = {})
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("Alice's row").assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        return recheck
    }

    /**
     * The tests that hold the clock let what they started (the rows' fade, the new user's list) run
     * to its end, or it is still due in the next test class on the same main looper.
     */
    @After
    fun letAnimationsEnd() {
        rule.mainClock.autoAdvance = true
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    /** One frame after the client switched to Bob (UsersViewModel.switchTo). */
    private fun switchClientToBob(session: SwitchableSession, recheck: (() -> Unit)?) {
        session.user = bob.id
        recheck?.invoke()
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
    }

    /**
     * #872: a switch on the profile sheet keeps Home on screen, and the previous user's rows stayed
     * there for a couple of frames after the sheet closed, until the ViewModel heard of the switch
     * from Room. The sheet's switch makes Home read the session's user again, in the frame the
     * sheet closes.
     */
    @Test
    fun aProfileSheetSwitchHidesThePreviousUsersRowsInTheNextFrame() {
        val session = SwitchableSession(alice.id)
        val recheck = setHomeOfSession(session)

        switchClientToBob(session, recheck)

        rule.onAllNodes(hasText("Alice's row")).assertCountEquals(0)
    }

    /**
     * #872, the cause: the session's user is no snapshot state, so its change alone recomposes
     * nothing and Home goes on showing what it showed. This is what main did after the sheet's
     * switch.
     */
    @Test
    fun theSessionsUserAloneDoesNotRecomposeHome() {
        val session = SwitchableSession(alice.id)
        setHomeOfSession(session)

        switchClientToBob(session, recheck = null)

        rule.onNodeWithText("Alice's row").assertExists()
    }

    /**
     * #392: the card keeps what #377 gave the Home error: details in words, Retry, Share, Close.
     */
    @Test
    fun theErrorCardOpensTheErrorDetails() {
        setLayout(loaded.copy(error = Exception("Failed to connect to /10.0.2.2:8096")))

        rule.onNodeWithContentDescription(string(CoreR.string.show_stacktrace)).performClick()
        rule.mainClock.advanceTimeBy(1_000)

        rule.onNodeWithText(string(CoreR.string.retry)).assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.share)).assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.close)).assertIsDisplayed()
        // No stack trace on screen: its frames name this test class.
        rule.onAllNodes(hasText("HomeScreenLayoutTest", substring = true)).assertCountEquals(0)
    }
}
