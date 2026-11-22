package dev.jdtech.jellyfin.presentation.film

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.platform.UriHandler
import dev.jdtech.jellyfin.PlayerActivity
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.asString

/**
 * CE (#332): plays a local trailer or special feature in the app's own player. The player loads an
 * extra the way it loads a movie: one video item, no queue.
 */
internal fun playExtra(context: Context, extraId: ItemId) {
    val intent = Intent(context, PlayerActivity::class.java)
    intent.putExtra("itemId", extraId.asString())
    intent.putExtra("itemKind", ItemKind.MOVIE.serialName)
    context.startActivity(intent)
}

/** CE (#332): opens an IMDb, TMDB, TVDB… page in the browser. */
internal fun openExternalLink(context: Context, uriHandler: UriHandler, url: String) {
    try {
        uriHandler.openUri(url)
    } catch (e: IllegalArgumentException) {
        Toast.makeText(context, e.localizedMessage, Toast.LENGTH_SHORT).show()
    }
}
