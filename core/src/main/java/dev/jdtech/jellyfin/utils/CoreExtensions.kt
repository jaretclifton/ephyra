package dev.jdtech.jellyfin.utils

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.res.Resources
import android.util.Base64
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.View
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun FindroidCollection.toView(items: List<FindroidItem>): View {
    return View(id = id, name = name, items = items, type = type)
}

fun Resources.dip(px: Int) = (px * displayMetrics.density).toInt()

fun Activity.restart() {
    val intent = Intent(this, this::class.java)
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    startActivity(intent)
}

fun String.base64ToByteArray(): ByteArray {
    return Base64.decode(toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
}

fun ByteArray.toBase64Str(): String {
    return Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_WRAP)
}

fun LocalDateTime.format(locale: Locale = Locale.getDefault()): String {
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
        .format(this.toLocalDate())
}

fun BroadcastReceiver.launchAsync(
    coroutineScope: CoroutineScope,
    block: suspend CoroutineScope.() -> Unit,
) {
    val pendingResult = goAsync()
    coroutineScope.launch(block = block).invokeOnCompletion { pendingResult.finish() }
}
