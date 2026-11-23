package dev.jdtech.jellyfin.presentation.film.components

import android.content.Context
import android.content.Intent
import dev.jdtech.jellyfin.PlayerActivity
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.asString

/** Whether [playDownloadedItem] can start [item] directly. */
fun canPlayDirectly(item: Item): Boolean = item is Movie || item is Episode

/** Resumes [item] in the player. The player picks the downloaded copy over streaming on its own. */
fun Context.playDownloadedItem(item: Item) {
    val kind =
        when (item) {
            is Movie -> ItemKind.MOVIE
            is Episode -> ItemKind.EPISODE
            else -> return
        }
    val intent =
        Intent(this, PlayerActivity::class.java)
            .putExtra("itemId", item.id.asString())
            .putExtra("itemKind", kind.serialName)
            .putExtra("startFromBeginning", false)
    startActivity(intent)
}
