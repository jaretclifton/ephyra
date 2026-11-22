package dev.jdtech.jellyfin.presentation.film

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import dev.jdtech.jellyfin.core.presentation.dummy.dummyCollections
import dev.jdtech.jellyfin.core.presentation.session.LocalServerSession
import dev.jdtech.jellyfin.film.presentation.media.MediaAction
import dev.jdtech.jellyfin.film.presentation.media.MediaState
import dev.jdtech.jellyfin.film.presentation.media.MediaViewModel
import dev.jdtech.jellyfin.film.presentation.search.SearchAction
import dev.jdtech.jellyfin.film.presentation.search.SearchState
import dev.jdtech.jellyfin.film.presentation.search.SearchViewModel
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.presentation.components.ErrorDialog
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.DownloadsOnlyLine
import dev.jdtech.jellyfin.presentation.film.components.ErrorCardInitialHeight
import dev.jdtech.jellyfin.presentation.film.components.FilmSearchBar
import dev.jdtech.jellyfin.presentation.film.components.ItemCard
import dev.jdtech.jellyfin.presentation.film.components.ServerOrErrorCard
import dev.jdtech.jellyfin.presentation.film.components.TopContentScrim
import dev.jdtech.jellyfin.presentation.film.components.showsServerOrErrorCard
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.GridCellsAdaptiveWithMinColumns
import dev.jdtech.jellyfin.presentation.utils.KeyboardShortcuts
import dev.jdtech.jellyfin.presentation.utils.Layout
import dev.jdtech.jellyfin.presentation.utils.LocalServerStatus
import dev.jdtech.jellyfin.presentation.utils.ReloadWhenServerAwayChanges
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import dev.jdtech.jellyfin.presentation.utils.showsServerIndicator
import dev.jdtech.jellyfin.presentation.utils.useDockedSearch
import dev.jdtech.jellyfin.repository.StoredLibraries

@Composable
fun MediaScreen(
    onItemClick: (FindroidItem) -> Unit,
    searchExpanded: Boolean,
    onSearchExpand: (Boolean) -> Unit,
    viewModel: MediaViewModel = hiltViewModel(),
    searchViewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val searchState by searchViewModel.state.collectAsStateWithLifecycle()

    // CE (#775): with the server away the libraries and the search are the downloads, so both load
    // again when it goes and when it comes back.
    val serverAway = LocalServerStatus.current.unreachable
    ReloadWhenServerAwayChanges { away ->
        viewModel.onServerAwayChanged(away)
        searchViewModel.onServerAwayChanged(away)
    }

    // Reloads only when stale or dirty (#251), and keeps the list on screen while it does.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // CE (#775): told first, so a resume after a change loads once, not twice.
        viewModel.onServerAwayChanged(serverAway)
        if (viewModel.shouldReload()) viewModel.loadData(silent = state.libraries.isNotEmpty())
    }

    // CE (R8): Ctrl+F opens the search bar, which focuses its field when it expands.
    KeyboardShortcuts.OnFind { if (!searchExpanded) onSearchExpand(true) }
    // #430: Esc closes the open search, also when Tab has moved focus out of its field.
    if (searchExpanded) KeyboardShortcuts.OnEscape { onSearchExpand(false) }

    MediaScreenLayout(
        // CE (#810): never a frame of the previous user's libraries after a switch.
        state = state.shownTo(LocalServerSession.current.userId),
        // CE (#866): nor of their search results.
        searchState = searchState.shownTo(LocalServerSession.current.userId),
        searchExpanded = searchExpanded,
        onSearchExpand = onSearchExpand,
        onAction = { action ->
            when (action) {
                is MediaAction.OnItemClick -> onItemClick(action.item)
                else -> Unit
            }
            viewModel.onAction(action)
        },
        onSearchAction = { action ->
            when (action) {
                is SearchAction.OnItemClick -> onItemClick(action.item)
                else -> Unit
            }
            searchViewModel.onAction(action)
        },
    )
}

@Composable
internal fun MediaScreenLayout(
    state: MediaState,
    searchState: SearchState,
    searchExpanded: Boolean,
    onSearchExpand: (Boolean) -> Unit,
    onAction: (MediaAction) -> Unit,
    onSearchAction: (SearchAction) -> Unit,
) {
    val safePadding = rememberSafePadding(handleStartInsets = false)

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    // CE (#571): the grid starts under the error card at its real height, as on Home.
    val density = LocalDensity.current
    var errorCardHeight by remember { mutableStateOf(ErrorCardInitialHeight) }
    // CE (#281): the same slot holds the unreachable banner, also when no load has failed.
    // CE (#775): with no libraries on screen the banner stays: it is what says why.
    val hasContent = state.libraries.isNotEmpty()
    val showCard = showsServerOrErrorCard(state.error, hasContent = hasContent)
    val contentPaddingTop by
        animateDpAsState(
            targetValue =
                if (showCard) {
                    safePadding.top + 80.dp + errorCardHeight + MaterialTheme.spacings.medium
                } else {
                    safePadding.top + 88.dp
                },
            label = "content_padding",
        )

    var showErrorDialog by rememberSaveable { mutableStateOf(false) }
    // CE (#775): what is on screen decides, not the server's state: the real libraries can stay
    // after the server went away (nothing downloaded), and the stored ones are downloads only.
    val downloadsOnly =
        remember(state.libraries) {
            state.libraries.isNotEmpty() && state.libraries.all { StoredLibraries.isStored(it.id) }
        }

    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val minColumnSize =
        when {
            windowSizeClass.isWidthAtLeastBreakpoint(
                WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND
            ) -> 320.dp
            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) ->
                240.dp
            else -> 160.dp
        }

    Box(modifier = Modifier.fillMaxSize()) {
        FilmSearchBar(
            state = searchState,
            expanded = searchExpanded,
            onExpand = onSearchExpand,
            onAction = onSearchAction,
            // Capped to a readable width while collapsed; the expanded results fill the content
            // area (the window on a phone, the room beside the rail when docked, #439).
            modifier =
                if (searchExpanded) Modifier.fillMaxWidth()
                else Modifier.widthIn(max = Layout.textMaxWidth),
            paddingStart = paddingStart,
            paddingEnd = paddingEnd,
            docked = useDockedSearch(),
            serverIndicator = showsServerIndicator(hasContent = hasContent),
        )
        LazyVerticalGrid(
            columns = GridCellsAdaptiveWithMinColumns(minSize = minColumnSize, minColumns = 2),
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = paddingStart,
                    top = contentPaddingTop,
                    end = paddingEnd,
                    bottom = paddingBottom,
                ),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
        ) {
            // CE (#775): the stored libraries (Movies, Shows) hold the downloads.
            if (downloadsOnly) {
                item(key = "downloads_only", span = { GridItemSpan(maxLineSpan) }) {
                    DownloadsOnlyLine(modifier = Modifier.animateItem())
                }
            }
            items(state.libraries, key = { it.id }) { library ->
                ItemCard(
                    item = library,
                    direction = Direction.HORIZONTAL,
                    onClick = { onAction(MediaAction.OnItemClick(library)) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        TopContentScrim(height = safePadding.top + 88.dp)
        if (showCard) {
            ServerOrErrorCard(
                error = state.error,
                onShowStacktrace = { showErrorDialog = true },
                onRetryClick = { onAction(MediaAction.OnRetryClick) },
                hasContent = hasContent,
                modifier =
                    Modifier.padding(
                            start = paddingStart,
                            top = safePadding.top + 80.dp,
                            end = paddingEnd,
                        )
                        .onSizeChanged { errorCardHeight = with(density) { it.height.toDp() } },
            )
            if (state.error != null && showErrorDialog) {
                ErrorDialog(
                    exception = state.error!!,
                    onDismissRequest = { showErrorDialog = false },
                    onRetry = { onAction(MediaAction.OnRetryClick) },
                )
            }
        }
    }
}

@PreviewScreenSizes
@Composable
private fun MediaScreenLayoutPreview() {
    FindroidTheme {
        MediaScreenLayout(
            state =
                MediaState(libraries = dummyCollections, error = Exception("Failed to load data")),
            searchState = SearchState(),
            searchExpanded = false,
            onSearchExpand = {},
            onAction = {},
            onSearchAction = {},
        )
    }
}
