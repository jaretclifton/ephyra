package dev.jdtech.jellyfin.presentation.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.jdtech.jellyfin.core.presentation.localnetwork.LocalLocalNetworkBlock
import dev.jdtech.jellyfin.core.presentation.localnetwork.UnblockRetry
import dev.jdtech.jellyfin.utils.LoadErrorText

/**
 * CE (#281): what the screens know about the server while the app runs.
 *
 * [unreachable] shows the banner and the "Downloads" action of the error page. Only
 * [confirmedUnreachable] takes away what needs the server (downloading, trailers): one failed probe
 * can be a slow server, and the buttons must not flash for that. [hasDownloads] says whether there
 * is anything in Downloads to point at. [check] asks again without waiting for the answer.
 * [openDownloads] opens the Downloads tab; null where there is no such tab to go to (setup, the
 * Downloads tab itself).
 *
 * CE (#775): [bannerDismissed] is the banner put away with [dismissBanner] until the server has
 * answered again; [showBanner] brings it back.
 */
@Immutable
data class ServerStatusUi(
    val unreachable: Boolean = false,
    val confirmedUnreachable: Boolean = false,
    val hasDownloads: Boolean = true,
    val check: () -> Unit = {},
    val openDownloads: (() -> Unit)? = null,
    val bannerDismissed: Boolean = false,
    val dismissBanner: () -> Unit = {},
    val showBanner: () -> Unit = {},
)

/** Reachable unless provided otherwise, so previews and tests need not provide it. */
val LocalServerStatus = compositionLocalOf { ServerStatusUi() }

/**
 * Whether the screens treat the server as away: the banner, its indicator and the error page. Not
 * while Android's local network permission is what keeps the server away: then the error card
 * of #571 names the permission and asks for it.
 */
@Composable
fun isServerAway(): Boolean =
    LocalServerStatus.current.unreachable && !LocalLocalNetworkBlock.current.blocked

/**
 * Whether a screen shows the unreachable banner: the server is away and the banner not put away.
 */
@Composable
fun showsServerBanner(): Boolean = isServerAway() && !LocalServerStatus.current.bannerDismissed

/**
 * CE (#775): whether a screen shows the quiet "server off" icon in its header in place of the
 * banner, which the user put away.
 */
@Composable
fun showsServerIndicator(): Boolean = isServerAway() && LocalServerStatus.current.bannerDismissed

/**
 * Whether [error] is the server being unreachable, as the banner already says: a connection error
 * while the server is away. Also with the banner put away (#775): the error is still explained.
 */
@Composable
fun isServerUnreachable(error: Throwable?): Boolean {
    val connectionError =
        remember(error) { error != null && LoadErrorText.isConnectionError(error) }
    return connectionError && isServerAway()
}

/**
 * Loads again, once, when the server answers again while [error] is on screen because it did not.
 * The rule is [UnblockRetry]'s: only the step from "explained by the server being away" to "the
 * server is back" retries, so a retry that fails for another reason shows that error and stops.
 */
@Composable
fun RetryWhenServerReturns(error: Throwable?, onRetry: () -> Unit) {
    val unreachable = LocalServerStatus.current.unreachable
    val connectionError =
        remember(error) { error != null && LoadErrorText.isConnectionError(error) }
    val explained = connectionError && unreachable
    val retry = remember { UnblockRetry() }
    val currentOnRetry by rememberUpdatedState(onRetry)
    val stillFailed by rememberUpdatedState(connectionError)
    LaunchedEffect(explained, unreachable) {
        // Not when the error is gone already: the user's own retry was what reached the server.
        if (retry.shouldRetry(explained, unreachable) && stillFailed) currentOnRetry()
    }
}

/**
 * CE (#775): tells [onChange] whether the server is away, at first and at every change, so a list
 * that falls back to the downloads loads again when the server goes and when it comes back. The
 * view model decides whether that takes a load: it knows what its list was loaded with, also after
 * the screen was out of view (another tab), and whether such a load is running already.
 */
@Composable
fun ReloadWhenServerAwayChanges(onChange: (away: Boolean) -> Unit) {
    val away = LocalServerStatus.current.unreachable
    val currentOnChange by rememberUpdatedState(onChange)
    LaunchedEffect(away) { currentOnChange(away) }
}
