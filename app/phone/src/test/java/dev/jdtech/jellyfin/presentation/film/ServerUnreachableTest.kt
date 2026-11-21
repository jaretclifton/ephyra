package dev.jdtech.jellyfin.presentation.film

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeRight
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import dev.jdtech.jellyfin.core.presentation.dummy.dummyCollections
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeSection
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServer
import dev.jdtech.jellyfin.core.presentation.localnetwork.LocalLocalNetworkBlock
import dev.jdtech.jellyfin.core.presentation.localnetwork.LocalNetworkBlock
import dev.jdtech.jellyfin.film.presentation.collection.CollectionAction
import dev.jdtech.jellyfin.film.presentation.collection.CollectionState
import dev.jdtech.jellyfin.film.presentation.home.HomeAction
import dev.jdtech.jellyfin.film.presentation.home.HomeState
import dev.jdtech.jellyfin.film.presentation.library.LibraryState
import dev.jdtech.jellyfin.film.presentation.media.MediaState
import dev.jdtech.jellyfin.film.presentation.search.SearchState
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidTrailer
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.presentation.film.components.ErrorState
import dev.jdtech.jellyfin.presentation.film.components.ItemButtonsBar
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.utils.LocalServerStatus
import dev.jdtech.jellyfin.presentation.utils.ServerStatusUi
import dev.jdtech.jellyfin.repository.StoredLibraries
import dev.jdtech.jellyfin.setup.R as SetupR
import java.net.ConnectException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CE (#281): while the server does not answer, the screens say so in a banner that leads to
 * Downloads, instead of "Error loading data" and no way on. Nothing is blocked by it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h914dp")
class ServerUnreachableTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var status by mutableStateOf(ServerStatusUi())
    private var homeState by mutableStateOf(HomeState())
    private var error: Exception by mutableStateOf(ConnectException("Failed to connect"))
    private var localNetworkBlocked by mutableStateOf(false)
    private var downloadsOpened = 0
    private var checks = 0
    private var retries = 0

    private fun string(id: Int) = rule.activity.getString(id)

    private val unreachableText
        get() = string(CoreR.string.download_error_server_unreachable)

    private val downloadsLine
        get() = string(CoreR.string.ce_server_unreachable_downloads)

    private fun unreachable(confirmed: Boolean = true, hasDownloads: Boolean = true) =
        ServerStatusUi(
            unreachable = true,
            confirmedUnreachable = confirmed,
            hasDownloads = hasDownloads,
            check = { checks++ },
            openDownloads = { downloadsOpened++ },
        )

    private val loaded =
        HomeState(
            server = dummyServer.copy(name = "Stable Demo"),
            resumeSection = dummyHomeSection,
            isLoading = false,
        )

    private fun setContent(content: @Composable () -> Unit) {
        rule.setContent {
            FindroidTheme {
                CompositionLocalProvider(
                    LocalServerStatus provides status,
                    LocalLocalNetworkBlock provides
                        LocalNetworkBlock(blocked = localNetworkBlocked, request = {}),
                ) {
                    content()
                }
            }
        }
        settle()
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    private fun setHome(initial: HomeState) {
        homeState = initial
        setContent {
            HomeScreenLayout(
                state = homeState,
                onAction = { if (it is HomeAction.OnRetryClick) retries++ },
            )
        }
    }

    private fun setErrorState() = setContent {
        ErrorState(error = error, onRetryClick = { retries++ })
    }

    private fun assertAbsent(text: String) = rule.onAllNodes(hasText(text)).assertCountEquals(0)

    // Home

    @Test
    fun homeShowsNoBannerWhileTheServerAnswers() {
        setHome(loaded)

        assertAbsent(unreachableText)
    }

    @Test
    fun homeShowsTheBannerWithoutAnyFailedLoad() {
        status = unreachable()
        setHome(loaded)

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        rule.onNodeWithText(downloadsLine).assertIsDisplayed()
        // The rows stay: nothing is taken away.
        rule.onNodeWithText("Stable Demo").assertIsDisplayed()
    }

    @Test
    fun theBannerLeadsToDownloadsAndRetries() {
        status = unreachable()
        setHome(loaded)

        rule.onNodeWithText(string(CoreR.string.title_download)).performClick()
        assertEquals(1, downloadsOpened)

        rule.onNodeWithText(string(CoreR.string.retry)).performClick()
        // Retry asks the server again and loads the screen again.
        assertEquals(1, checks)
        assertEquals(1, retries)
    }

    @Test
    fun theBannerStandsInForTheRedCardWhenTheLoadFailedForThatReason() {
        status = unreachable()
        setHome(loaded.copy(error = ConnectException("Failed to connect")))

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        assertAbsent(string(CoreR.string.error_loading_data))
    }

    @Test
    fun anAnswerWithAServerErrorKeepsTheRedCard() {
        // HTTP 500 came from somewhere: that is an error to show, not "can't reach".
        status = unreachable()
        setHome(loaded.copy(error = InvalidStatusException(500, null)))

        rule.onNodeWithText(string(CoreR.string.error_loading_data)).assertIsDisplayed()
        assertAbsent(unreachableText)
    }

    @Test
    fun withoutDownloadsTheBannerOnlySaysSoAndOffersRetry() {
        status = unreachable(hasDownloads = false)
        setHome(loaded)

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.retry)).assertIsDisplayed()
        assertAbsent(downloadsLine)
        assertAbsent(string(CoreR.string.title_download))
    }

    @Test
    fun theRowsStartUnderTheBanner() {
        status = unreachable()
        setHome(loaded)

        val bannerBottom =
            rule.onNodeWithText(string(CoreR.string.title_download)).getBoundsInRoot().bottom
        val rowTop = rule.onNodeWithText("Continue watching").getBoundsInRoot().top
        assertTrue(rowTop > bannerBottom, message = "rows at $rowTop, banner ends at $bannerBottom")
    }

    @Test
    fun theBannerGoesWhenTheServerAnswersAndHomeLoadsAgainOnce() {
        status = unreachable()
        setHome(loaded.copy(error = ConnectException("Failed to connect")))
        assertEquals(0, retries)

        status = ServerStatusUi()
        settle()

        assertAbsent(unreachableText)
        assertEquals(1, retries)

        // The reload failed for another reason: that error shows, and nothing retries again.
        homeState = loaded.copy(error = InvalidStatusException(500, null))
        settle()
        rule.onNodeWithText(string(CoreR.string.error_loading_data)).assertIsDisplayed()
        assertEquals(1, retries)
    }

    @Test
    fun aBannerWithNothingFailedReloadsNothingWhenTheServerReturns() {
        status = unreachable()
        setHome(loaded)

        status = ServerStatusUi()
        settle()

        assertEquals(0, retries)
    }

    @Test
    fun anEmptyHomeThatFailedShowsOnePageNotTheBannerToo() {
        status = unreachable()
        setHome(
            HomeState(
                server = dummyServer,
                isLoading = false,
                error = ConnectException("Failed to connect"),
            )
        )

        // The Home cache is off or empty: the page says it once, with the way to Downloads.
        rule.onAllNodes(hasText(unreachableText)).assertCountEquals(1)
        rule.onNodeWithText(downloadsLine).assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.title_download)).performClick()
        assertEquals(1, downloadsOpened)
    }

    @Test
    fun theLocalNetworkPermissionKeepsItsOwnCard() {
        // Android 17 with local network access off (#571): that card names the permission.
        localNetworkBlocked = true
        status = unreachable()
        setHome(loaded.copy(error = ConnectException("Failed to connect")))

        rule.onNodeWithText(string(SetupR.string.ce_local_network_off)).assertIsDisplayed()
        assertAbsent(unreachableText)
    }

    // Favorites

    private fun setFavorites(state: CollectionState) = setContent {
        CollectionScreenLayout(
            collectionName = "Favorites",
            state = state,
            onAction = { if (it is CollectionAction.OnRetryClick) retries++ },
            emptyTitle = "No favorites",
            showNavigationIcon = false,
            serverBanner = true,
        )
    }

    @Test
    fun favoritesWithNothingInThemStillSayTheServerIsAway() {
        status = unreachable()
        setFavorites(CollectionState(isLoading = false))

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        rule.onNodeWithText("No favorites").assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.title_download)).performClick()
        assertEquals(1, downloadsOpened)
    }

    @Test
    fun favoritesThatFailedWithNothingToShowSayItOnce() {
        status = unreachable()
        setFavorites(CollectionState(isLoading = false, error = ConnectException("Failed")))

        rule.onAllNodes(hasText(unreachableText)).assertCountEquals(1)
    }

    // The error page of the other screens

    @Test
    fun theErrorPageLeadsToDownloadsWhileTheServerIsAway() {
        status = unreachable()
        setErrorState()

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        rule.onNodeWithText(downloadsLine).assertIsDisplayed()
        assertAbsent(string(CoreR.string.show_stacktrace))

        rule.onNodeWithText(string(CoreR.string.title_download)).performClick()
        assertEquals(1, downloadsOpened)

        rule.onNodeWithText(string(CoreR.string.retry)).performClick()
        assertEquals(1, checks)
        assertEquals(1, retries)
    }

    @Test
    fun theErrorPageWithoutDownloadsOffersRetryOnly() {
        status = unreachable(hasDownloads = false)
        setErrorState()

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.retry)).assertIsDisplayed()
        assertAbsent(string(CoreR.string.title_download))
        assertAbsent(downloadsLine)
    }

    @Test
    fun theErrorPageOnTheDownloadsTabDoesNotPointAtItself() {
        status = unreachable().copy(openDownloads = null)
        setErrorState()

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        assertAbsent(string(CoreR.string.title_download))
    }

    @Test
    fun theErrorPageIsTheUsualOneWhileTheServerAnswers() {
        setErrorState()

        rule.onNodeWithText(string(CoreR.string.error_loading_data)).assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.show_stacktrace)).assertIsDisplayed()
        assertAbsent(string(CoreR.string.title_download))
    }

    @Test
    fun anErrorTheServerSentKeepsTheUsualPageWhileTheBannerIsUp() {
        status = unreachable()
        error = InvalidStatusException(500, null)
        setErrorState()

        rule.onNodeWithText(string(CoreR.string.error_loading_data)).assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.show_stacktrace)).assertIsDisplayed()
    }

    @Test
    fun theErrorPageLoadsAgainOnceWhenTheServerReturns() {
        status = unreachable()
        setErrorState()
        assertEquals(0, retries)

        status = ServerStatusUi()
        settle()
        assertEquals(1, retries)

        // Still failing, for whatever reason: the usual page, and no loop.
        settle()
        assertEquals(1, retries)
        rule.onNodeWithText(string(CoreR.string.error_loading_data)).assertIsDisplayed()
    }

    // Dismissing the banner (#775)

    private var bannerShows = 0

    /** Unreachable, with dismiss and show acting on [status] as MainViewModel does. */
    private fun dismissible(hasDownloads: Boolean = true): ServerStatusUi =
        unreachable(hasDownloads = hasDownloads)
            .copy(
                dismissBanner = { status = status.copy(bannerDismissed = true) },
                showBanner = {
                    bannerShows++
                    status = status.copy(bannerDismissed = false)
                },
            )

    private val closeText
        get() = string(CoreR.string.close)

    private val indicatorText
        get() = string(CoreR.string.ce_server_banner_show)

    private val downloadsOnlyText
        get() = string(CoreR.string.ce_downloads_only)

    private fun hasAnyCustomAction() = SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)

    private fun assertNoIndicator() =
        rule.onAllNodes(hasContentDescription(indicatorText)).assertCountEquals(0)

    @Test
    fun closingTheBannerHidesItAndTheRowsMoveUp() {
        status = dismissible()
        setHome(loaded)
        val rowTopWithBanner = rule.onNodeWithText("Continue watching").getBoundsInRoot().top

        rule.onNodeWithContentDescription(closeText).performClick()
        settle()

        assertAbsent(unreachableText)
        val rowTop = rule.onNodeWithText("Continue watching").getBoundsInRoot().top
        assertTrue(
            rowTop < rowTopWithBanner,
            message = "rows at $rowTop, were at $rowTopWithBanner",
        )
    }

    @Test
    fun aSwipeSidewaysPutsTheBannerAway() {
        status = dismissible()
        setHome(loaded)

        rule.onNodeWithText(unreachableText).performTouchInput { swipeRight() }
        settle()

        assertAbsent(unreachableText)
        assertTrue(status.bannerDismissed)
    }

    @Test
    fun aShortDragLeavesTheBannerWhereItIs() {
        status = dismissible()
        setHome(loaded)

        rule.onNodeWithText(unreachableText).performTouchInput {
            swipe(start = centerLeft, end = centerLeft + Offset(width * 0.1f, 0f))
        }
        settle()

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        assertTrue(!status.bannerDismissed)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theScreenReaderCanPutTheBannerAway() {
        status = dismissible()
        setHome(loaded)

        rule
            .onNode(hasText(unreachableText, substring = true) and hasAnyCustomAction())
            .performCustomAccessibilityActionWithLabel(closeText)
        settle()

        assertAbsent(unreachableText)
    }

    @Test
    fun theBannerWithoutDownloadsCanBeClosedToo() {
        // The one-row layout: Retry and the close button.
        status = dismissible(hasDownloads = false)
        setHome(loaded)

        rule.onNodeWithContentDescription(closeText).performClick()
        settle()

        assertAbsent(unreachableText)
    }

    @Test
    fun theIndicatorShowsOnlyWhileAwayAndPutAway() {
        setHome(loaded)
        assertNoIndicator()

        status = dismissible()
        settle()
        // The banner is up: no icon as well.
        assertNoIndicator()

        rule.onNodeWithContentDescription(closeText).performClick()
        settle()
        rule.onNodeWithContentDescription(indicatorText).assertIsDisplayed()

        // The server answers: neither the banner nor the icon.
        status = status.copy(unreachable = false, confirmedUnreachable = false)
        settle()
        assertNoIndicator()
        assertAbsent(unreachableText)
    }

    @Test
    fun theIndicatorBringsTheBannerBack() {
        status = dismissible().copy(bannerDismissed = true)
        setHome(loaded)
        assertAbsent(unreachableText)

        rule.onNodeWithContentDescription(indicatorText).performClick()
        settle()

        assertEquals(1, bannerShows)
        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        assertNoIndicator()
    }

    @Test
    fun theErrorPageCannotBePutAway() {
        status = dismissible().copy(bannerDismissed = true)
        setErrorState()

        // Still the page that says the server is away, with no close button.
        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        rule.onAllNodes(hasContentDescription(closeText)).assertCountEquals(0)
    }

    @Test
    fun anEmptyHomeThatFailedShowsNoIndicatorOverItsPage() {
        status = dismissible().copy(bannerDismissed = true)
        setHome(
            HomeState(
                server = dummyServer,
                isLoading = false,
                error = ConnectException("Failed to connect"),
            )
        )

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        assertNoIndicator()
    }

    @Test
    fun theRedCardForAnotherErrorHasNoCloseButton() {
        status = dismissible()
        setHome(loaded.copy(error = InvalidStatusException(500, null)))

        rule.onNodeWithText(string(CoreR.string.error_loading_data)).assertIsDisplayed()
        rule.onAllNodes(hasContentDescription(closeText)).assertCountEquals(0)
    }

    @Test
    fun aConnectionErrorStaysQuietWithTheBannerPutAway() {
        // The banner stood in for the red card; put away, nothing takes its place.
        status = dismissible().copy(bannerDismissed = true)
        setHome(loaded.copy(error = ConnectException("Failed to connect")))

        assertAbsent(unreachableText)
        assertAbsent(string(CoreR.string.error_loading_data))
        rule.onNodeWithContentDescription(indicatorText).assertIsDisplayed()
    }

    // "Showing downloads only" (#775)

    @Test
    fun homeDoesNotSayDownloadsOnly() {
        status = unreachable()
        setHome(loaded)

        assertAbsent(downloadsOnlyText)
    }

    @Test
    fun favoritesLoadedWhileAwaySayTheyAreTheDownloadsOnly() {
        status = dismissible()
        setFavorites(CollectionState(isLoading = false, downloadsOnly = true))
        rule.onNodeWithText(downloadsOnlyText).assertIsDisplayed()

        // Also with the banner put away, next to the icon that brings it back.
        rule.onNodeWithContentDescription(closeText).performClick()
        settle()
        rule.onNodeWithText(downloadsOnlyText).assertIsDisplayed()
        rule.onNodeWithContentDescription(indicatorText).assertIsDisplayed()
    }

    @Test
    fun favoritesFromTheServerSayNothingWhileItIsAway() {
        // Loaded while the server answered; the reload for the outage has not come back yet.
        status = unreachable()
        setFavorites(favorites(downloadsOnly = false))

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        assertAbsent(downloadsOnlyText)
    }

    @Test
    fun aStaleFavoritesListWithAnErrorSaysNothing() {
        // Nothing downloaded: the reload failed and the full list from before stays.
        status = unreachable()
        setFavorites(favorites(downloadsOnly = false).copy(error = ConnectException("Failed")))

        rule.onNodeWithText("Favorite movie").assertIsDisplayed()
        assertAbsent(downloadsOnlyText)
    }

    private fun favorites(downloadsOnly: Boolean) =
        CollectionState(
            sections =
                listOf(
                    CollectionSection(
                        id = 0,
                        name = UiText.StringResource(CoreR.string.movies_label),
                        items = listOf(dummyMovie.copy(name = "Favorite movie")),
                    )
                ),
            isLoading = false,
            downloadsOnly = downloadsOnly,
        )

    // Media and a library: the stored libraries are the downloads, whatever the server does

    private val storedLibraries =
        listOf(
            dummyCollections[0].copy(id = StoredLibraries.MOVIES),
            dummyCollections[1].copy(id = StoredLibraries.SHOWS),
        )

    private fun setMedia(state: MediaState) = setContent {
        MediaScreenLayout(
            state = state,
            searchState = SearchState(),
            searchExpanded = false,
            onSearchExpand = {},
            onAction = {},
            onSearchAction = {},
        )
    }

    @Test
    fun mediaWithTheStoredLibrariesSaysDownloadsOnly() {
        status = unreachable()
        setMedia(MediaState(libraries = storedLibraries))

        rule.onNodeWithText(downloadsOnlyText).assertIsDisplayed()
    }

    @Test
    fun mediaWithTheRealLibrariesSaysNothingWhileTheServerIsAway() {
        // Loaded online; the quiet reload failed because nothing is downloaded.
        status = unreachable()
        setMedia(MediaState(libraries = dummyCollections))

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        assertAbsent(downloadsOnlyText)
    }

    private fun setLibrary(
        downloadsOnly: Boolean,
        items: PagingData<FindroidItem> = PagingData.from(listOf<FindroidItem>(dummyMovie)),
    ) = setContent {
        LibraryScreenLayout(
            libraryName = "Movies",
            state = LibraryState(items = flowOf(items)),
            onAction = {},
            downloadsOnly = downloadsOnly,
        )
    }

    /** A real library whose first load found the server away. */
    private val failedLibrary: PagingData<FindroidItem>
        get() =
            PagingData.from(
                emptyList(),
                sourceLoadStates =
                    LoadStates(
                        refresh = LoadState.Error(ConnectException("Failed to connect")),
                        prepend = LoadState.NotLoading(endOfPaginationReached = false),
                        append = LoadState.NotLoading(endOfPaginationReached = false),
                    ),
            )

    @Test
    fun aRealLibrarySaysNothingWhileTheServerIsAway() {
        // Its pages from the server stay in memory.
        status = unreachable()
        setLibrary(downloadsOnly = false)

        assertAbsent(downloadsOnlyText)
    }

    @Test
    fun aStoredLibrarySaysDownloadsOnlyAlsoWithTheServerBack() {
        setLibrary(downloadsOnly = true)

        rule.onNodeWithText(downloadsOnlyText).assertIsDisplayed()
    }

    @Test
    fun favoritesThatFailedSayNeitherDownloadsOnlyNorShowTheIcon() {
        status = dismissible().copy(bannerDismissed = true)
        setFavorites(CollectionState(isLoading = false, error = ConnectException("Failed")))

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        assertAbsent(downloadsOnlyText)
        assertNoIndicator()
    }

    @Test
    fun aCollectionIsNotTheDownloadsOnly() {
        // A collection screen (not Favorites) has no banner and no line.
        status = unreachable()
        setContent {
            CollectionScreenLayout(
                collectionName = "Marvel",
                state = CollectionState(isLoading = false),
                onAction = {},
            )
        }

        assertAbsent(downloadsOnlyText)
    }

    // Only over content can the banner be put away (#775)

    @Test
    fun mediaWithNothingOnScreenKeepsTheBannerEvenWhenItWasPutAway() {
        // No downloads and nothing loaded before the server went away.
        status = dismissible().copy(bannerDismissed = true)
        setMedia(MediaState(error = ConnectException("Failed to connect")))

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        rule.onAllNodes(hasContentDescription(closeText)).assertCountEquals(0)
        rule.onAllNodes(hasText(unreachableText) and hasAnyCustomAction()).assertCountEquals(0)
        assertNoIndicator()
    }

    @Test
    fun mediaWithLibrariesLetsTheBannerBePutAway() {
        status = dismissible()
        setMedia(MediaState(libraries = storedLibraries))

        rule.onNodeWithContentDescription(closeText).performClick()
        settle()

        assertAbsent(unreachableText)
        rule.onNodeWithContentDescription(indicatorText).assertIsDisplayed()
    }

    @Test
    fun aLibraryWhoseFirstLoadFailedKeepsTheBanner() {
        status = dismissible().copy(bannerDismissed = true)
        setLibrary(downloadsOnly = false, items = failedLibrary)

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        rule.onAllNodes(hasContentDescription(closeText)).assertCountEquals(0)
        assertNoIndicator()
    }

    @Test
    fun aLibraryWithItemsLetsTheBannerBePutAway() {
        status = dismissible().copy(bannerDismissed = true)
        setLibrary(downloadsOnly = false)

        assertAbsent(unreachableText)
        rule.onNodeWithContentDescription(indicatorText).assertIsDisplayed()
    }

    // Local network access off while the downloads are on screen (#775, #571)

    private val localNetworkOffText
        get() = string(SetupR.string.ce_local_network_off)

    @Test
    fun favoritesFromTheDownloadsStillOfferToAllowLocalNetworkAccess() {
        // Room answered, so no load failed: the card of #571 says what keeps the server away.
        localNetworkBlocked = true
        status = dismissible()
        setFavorites(favorites(downloadsOnly = true))

        rule.onNodeWithText(localNetworkOffText).assertIsDisplayed()
        assertAbsent(unreachableText)
        // Not something to put away, and no details of an error that is not there.
        rule.onAllNodes(hasContentDescription(closeText)).assertCountEquals(0)
        rule
            .onAllNodes(hasContentDescription(string(CoreR.string.show_stacktrace)))
            .assertCountEquals(0)
        assertNoIndicator()
    }

    @Test
    fun mediaMakesRoomForTheLocalNetworkCard() {
        localNetworkBlocked = true
        status = dismissible().copy(bannerDismissed = true)
        setMedia(MediaState(libraries = storedLibraries))

        val cardBottom = rule.onNodeWithText(localNetworkOffText).getBoundsInRoot().bottom
        val libraryTop = rule.onNodeWithText(storedLibraries[0].name).getBoundsInRoot().top
        assertTrue(
            libraryTop > cardBottom,
            message = "library at $libraryTop, card ends at $cardBottom",
        )
    }

    @Test
    fun theLocalNetworkCardRetriesAndAsksTheServerAgain() {
        localNetworkBlocked = true
        status = dismissible()
        setFavorites(favorites(downloadsOnly = true))

        rule.onNodeWithContentDescription(string(CoreR.string.retry)).performClick()

        assertEquals(1, checks)
        assertEquals(1, retries)
    }

    // Download and trailer buttons

    private fun setButtons() = setContent {
        ItemButtonsBar(
            item =
                dummyMovie.copy(
                    canDownload = true,
                    trailers = listOf(FindroidTrailer(name = null, url = "https://example.org/t")),
                ),
            onPlayClick = {},
            onMarkAsPlayedClick = {},
            onMarkAsFavoriteClick = {},
            onDownloadClick = {},
            onDownloadCancelClick = {},
            onDownloadDeleteClick = {},
            onTrailerClick = {},
            downloaderState = DownloaderState(),
            isDownloaded = false,
        )
    }

    private fun assertButtons(shown: Boolean) {
        val count = if (shown) 1 else 0
        rule
            .onAllNodes(hasContentDescription(string(CoreR.string.download_button_description)))
            .assertCountEquals(count)
        rule
            .onAllNodes(hasContentDescription(string(CoreR.string.trailer_button_description)))
            .assertCountEquals(count)
    }

    @Test
    fun downloadAndTrailerShowWhileTheServerAnswers() {
        setButtons()

        assertButtons(shown = true)
    }

    @Test
    fun oneFailedProbeAloneDoesNotTakeTheButtonsAway() {
        // A slow server looks like this for a moment: the banner may show, the buttons stay.
        status = unreachable(confirmed = false)
        setButtons()

        assertButtons(shown = true)
    }

    @Test
    fun theButtonsGoOnceTheServerIsKnownToBeAwayAndComeBack() {
        status = unreachable(confirmed = true)
        setButtons()
        assertButtons(shown = false)
        // Play stays: a download plays without the server.
        rule
            .onNodeWithContentDescription(string(CoreR.string.play_button_description))
            .assertExists()

        status = ServerStatusUi()
        settle()
        assertButtons(shown = true)
    }
}
