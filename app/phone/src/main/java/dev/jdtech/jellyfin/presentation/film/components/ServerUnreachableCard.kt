package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.ephyra.EphyraMotion
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.Layout
import dev.jdtech.jellyfin.presentation.utils.LocalServerStatus
import dev.jdtech.jellyfin.presentation.utils.RetryWhenServerReturns
import dev.jdtech.jellyfin.presentation.utils.ServerStatusUi
import dev.jdtech.jellyfin.presentation.utils.isServerAway
import dev.jdtech.jellyfin.presentation.utils.isServerUnreachable
import kotlin.math.abs
import kotlin.math.sign

/** From this width the banner is one row: text, then the buttons. */
private val OneRowMinWidth = 560.dp

/** A swipe past this part of the card's width, or a fling, puts the card away. */
private const val DismissFraction = 0.4f
private val DismissVelocity = 800.dp

/**
 * CE (#281): the server does not answer. A state, not a failed action, so it is not red: a neutral
 * card under the screen's header that blocks nothing. With downloads on the device it says they
 * still play and leads to them; without any it only says so and offers to try again.
 *
 * CE (#775): with [onDismissClick] the card can be put away: swiped sideways, the close button
 * (mouse, keyboard) or the screen reader's action. The header then shows [ServerOffIndicator].
 */
@Composable
fun ServerUnreachableCard(
    hasDownloads: Boolean,
    onRetryClick: () -> Unit,
    onDownloadsClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onDismissClick: (() -> Unit)? = null,
) {
    val downloads = onDownloadsClick.takeIf { hasDownloads }

    Surface(
        modifier =
            modifier
                .then(
                    if (onDismissClick != null) Modifier.swipeToDismiss(onDismissClick)
                    else Modifier
                )
                .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints {
            // One row when there is room, or when Retry is the only button.
            val oneRow = maxWidth >= OneRowMinWidth || downloads == null
            if (oneRow) {
                Row(
                    modifier =
                        Modifier.padding(
                            start = MaterialTheme.spacings.medium,
                            top = MaterialTheme.spacings.small,
                            end = MaterialTheme.spacings.small,
                            bottom = MaterialTheme.spacings.small,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Message(
                        showDownloadsLine = downloads != null,
                        onDismissClick = onDismissClick,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(MaterialTheme.spacings.small))
                    Buttons(onRetryClick = onRetryClick, onDownloadsClick = downloads)
                    if (onDismissClick != null) CloseButton(onClick = onDismissClick)
                }
            } else {
                Column(
                    modifier =
                        Modifier.padding(
                            start = MaterialTheme.spacings.medium,
                            // The close button brings its own room above the text.
                            top =
                                if (onDismissClick != null) MaterialTheme.spacings.small
                                else MaterialTheme.spacings.medium,
                            end = MaterialTheme.spacings.small,
                            bottom = MaterialTheme.spacings.small,
                        )
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Message(
                            showDownloadsLine = true,
                            onDismissClick = onDismissClick,
                            modifier = Modifier.weight(1f),
                        )
                        if (onDismissClick != null) CloseButton(onClick = onDismissClick)
                    }
                    Row(modifier = Modifier.align(Alignment.End)) {
                        Buttons(onRetryClick = onRetryClick, onDownloadsClick = downloads)
                    }
                }
            }
        }
    }
}

/**
 * CE (#775): follows a sideways drag and puts the card away past [DismissFraction] of its width or
 * on a fling; anything less springs back.
 */
@Composable
private fun Modifier.swipeToDismiss(onDismiss: () -> Unit): Modifier {
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val flingVelocity = with(LocalDensity.current) { DismissVelocity.toPx() }
    return onSizeChanged { width = it.width }
        .graphicsLayer {
            translationX = offset
            alpha = if (width == 0) 1f else 1f - (abs(offset) / width).coerceIn(0f, 1f)
        }
        .draggable(
            orientation = Orientation.Horizontal,
            state = rememberDraggableState { delta -> offset += delta },
            onDragStopped = { velocity ->
                val fling = abs(velocity) > flingVelocity && sign(velocity) == sign(offset)
                if (width > 0 && (abs(offset) > width * DismissFraction || fling)) {
                    animate(
                        initialValue = offset,
                        targetValue = sign(offset) * width,
                        animationSpec =
                            tween(EphyraMotion.DURATION_SHORT, easing = EphyraMotion.EasingExit),
                    ) { value, _ ->
                        offset = value
                    }
                    currentOnDismiss()
                } else {
                    animate(
                        initialValue = offset,
                        targetValue = 0f,
                        animationSpec =
                            tween(EphyraMotion.DURATION_MEDIUM, easing = EphyraMotion.EasingEnter),
                    ) { value, _ ->
                        offset = value
                    }
                }
            },
        )
}

@Composable
private fun RowScope.Message(
    showDownloadsLine: Boolean,
    onDismissClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Icon(
        painter = painterResource(CoreR.drawable.ic_server_off),
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.width(MaterialTheme.spacings.medium))
    // CE (#775): the screen reader's way to put the card away, as a swipe does.
    val dismissLabel = stringResource(CoreR.string.close)
    Column(
        modifier =
            modifier.semantics(mergeDescendants = true) {
                if (onDismissClick != null) {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(dismissLabel) {
                                onDismissClick()
                                true
                            }
                        )
                }
            }
    ) {
        Text(
            text = stringResource(CoreR.string.download_error_server_unreachable),
            style = MaterialTheme.typography.titleSmall,
        )
        if (showDownloadsLine) {
            Text(
                text = stringResource(CoreR.string.ce_server_unreachable_downloads),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Buttons(onRetryClick: () -> Unit, onDownloadsClick: (() -> Unit)?) {
    TextButton(onClick = onRetryClick) { Text(stringResource(CoreR.string.retry)) }
    if (onDownloadsClick != null) {
        Spacer(Modifier.width(MaterialTheme.spacings.small))
        FilledTonalButton(onClick = onDownloadsClick) {
            Text(stringResource(CoreR.string.title_download))
        }
    }
}

/** CE (#775): quiet, as the card's own icon: the card is a state, closing it is no big step. */
@Composable
private fun CloseButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_x),
            contentDescription = stringResource(CoreR.string.close),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * CE (#775): the banner put away, while the server is still away. A muted icon in the screen's
 * header, no red and no glow (a state, not an error); a tap brings the banner back.
 */
@Composable
fun ServerOffIndicator(modifier: Modifier = Modifier) {
    val status = LocalServerStatus.current
    IconButton(onClick = status.showBanner, modifier = modifier) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_server_off),
            contentDescription = stringResource(CoreR.string.ce_server_banner_show),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * CE (#775): a quiet line over a list that holds only the downloads while the server is away, so a
 * shorter list does not read as lost favourites.
 */
@Composable
fun DownloadsOnlyLine(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(CoreR.string.ce_downloads_only),
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        style = MaterialTheme.typography.labelMedium,
    )
}

/**
 * Whether [error] is the server not answering while it is away: the banner says that, or nothing
 * does when it is put away (#775).
 */
@Composable
private fun explainedByServerAway(error: Throwable?): Boolean =
    isServerAway() && (error == null || isServerUnreachable(error))

/**
 * Whether [ServerOrErrorCard] shows anything for [error], so a screen that lays the card over its
 * content can make room for it.
 */
@Composable
fun showsServerOrErrorCard(error: Throwable?): Boolean =
    if (explainedByServerAway(error)) !LocalServerStatus.current.bannerDismissed else error != null

/**
 * The card under a screen's header: the unreachable banner while the server does not answer, the
 * error card for any other failed load ([error]), nothing otherwise. The banner stands in for the
 * error card when the error is the server not answering, so one thing says it. Both load again with
 * [onRetryClick], and by themselves when the server is back. Only the banner can be put away
 * (#775); the error card stays.
 *
 * [modifier] carries the screen's padding. The width is set here: the error card fills it, the
 * banner stops at the width of a text column (docs/tablet/4-design.md).
 */
@Composable
fun ServerOrErrorCard(
    error: Throwable?,
    onShowStacktrace: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = LocalServerStatus.current
    RetryWhenServerReturns(error = error, onRetry = onRetryClick)

    if (explainedByServerAway(error)) {
        if (!status.bannerDismissed) {
            ServerUnreachableCard(
                hasDownloads = status.hasDownloads,
                onRetryClick = {
                    status.check()
                    onRetryClick()
                },
                onDownloadsClick = status.openDownloads,
                modifier = modifier.widthIn(max = Layout.textMaxWidth).fillMaxWidth(),
                onDismissClick = status.dismissBanner,
            )
        }
    } else if (error != null) {
        ErrorCard(
            onShowStacktrace = onShowStacktrace,
            onRetryClick = onRetryClick,
            modifier = modifier.fillMaxWidth(),
            error = error,
        )
    }
}

@Preview
@Composable
private fun ServerUnreachableCardPreview() {
    FindroidTheme {
        ServerUnreachableCard(
            hasDownloads = true,
            onRetryClick = {},
            onDownloadsClick = {},
            onDismissClick = {},
        )
    }
}

@Preview(widthDp = 640)
@Composable
private fun ServerUnreachableCardWidePreview() {
    FindroidTheme {
        ServerUnreachableCard(
            hasDownloads = true,
            onRetryClick = {},
            onDownloadsClick = {},
            onDismissClick = {},
        )
    }
}

@Preview
@Composable
private fun ServerUnreachableCardNoDownloadsPreview() {
    FindroidTheme {
        ServerUnreachableCard(
            hasDownloads = false,
            onRetryClick = {},
            onDownloadsClick = {},
            onDismissClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ServerOffIndicatorPreview() {
    FindroidTheme {
        CompositionLocalProvider(
            LocalServerStatus provides ServerStatusUi(unreachable = true, bannerDismissed = true)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ServerOffIndicator()
                DownloadsOnlyLine()
            }
        }
    }
}
