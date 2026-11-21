package dev.jdtech.jellyfin.presentation.film.components

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import coil3.compose.AsyncImage

/**
 * Image that moves on to the next of [sources] when one fails to load. Downloaded images always
 * have a path, whether or not the file exists, so a null check can't pick the fallback.
 */
@Composable
fun ArtworkImage(
    sources: List<String?>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val candidates =
        remember(sources) { sources.filterNotNull().distinct().map { it.resolveIn(context) } }
    var index by remember(candidates) { mutableIntStateOf(0) }

    AsyncImage(
        model = candidates.getOrNull(index),
        contentDescription = contentDescription,
        contentScale = contentScale,
        onError = { if (index < candidates.lastIndex) index++ },
        modifier = modifier,
    )
}

/**
 * The image to load for an image URL of `FindroidImages`. Downloaded images are stored as paths
 * relative to the files directory, without a scheme.
 */
internal fun String.resolveIn(context: Context): Uri {
    val uri = toUri()
    return if (uri.scheme == null) {
        Uri.Builder().appendEncodedPath("${context.filesDir}").appendEncodedPath(uri.path).build()
    } else uri
}
