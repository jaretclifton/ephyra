package dev.jdtech.jellyfin.core.presentation.ephyra

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.jdtech.jellyfin.core.R
import dev.jdtech.jellyfin.core.presentation.theme.OutfitFamily
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.looksLikeJellyfinId
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs

/**
 * Colours of an [EphyraAvatar]. Core has no Material 3, so the screens build this from their theme.
 *
 * @param containers (container, onContainer) pairs; a user's fallback picks one by id.
 */
@Immutable
data class AvatarColors(
    val containers: List<Pair<Color, Color>>,
    val badgeContainer: Color,
    val badgeContent: Color,
    val badgeRing: Color,
    /** CE (#299): the lock badge, a neutral chip colour (VISION �3.4). */
    val lockContainer: Color,
    val lockContent: Color,
)

/** Which of [count] colours a user gets. Deterministic, so a user keeps their colour. */
fun avatarColorIndex(seed: UserId, count: Int): Int =
    // The remainder before toInt(): abs(Int.MIN_VALUE) does not fit in an Int. The same index as
    // before for every other hash.
    (abs(idHash(seed.asString()).toLong()) % count).toInt()

/**
 * The hash a user's colour comes from. CE (#773): user ids were `java.util.UUID`s, and the colour
 * came from `UUID.hashCode()`. For an id in a UUID's dashed lowercase form (every Jellyfin id) this
 * is that same hash, so a user keeps their colour; any other id hashes as its text.
 */
private fun idHash(id: String): Int {
    if (!id.looksLikeJellyfinId()) return id.hashCode()
    val hex = id.replace("-", "")
    val mostSignificant = hex.substring(0, 16).toULong(16).toLong()
    val leastSignificant = hex.substring(16).toULong(16).toLong()
    // UUID.hashCode(): the xor of the four 32-bit halves.
    val hilo = mostSignificant xor leastSignificant
    return (hilo shr 32).toInt() xor hilo.toInt()
}

/**
 * The initials shown when a user has no picture: the first letter of the first and the last word of
 * [name], or "?" when there is none. Letters are whole graphemes, so an emoji is not cut in half.
 */
fun avatarInitials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    val picked = if (words.size == 1) listOf(words.first()) else listOf(words.first(), words.last())
    return picked.joinToString("") { it.firstGrapheme() }.uppercase(Locale.getDefault())
}

private fun String.firstGrapheme(): String {
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(this)
    val end = iterator.next()
    return if (end == BreakIterator.DONE) this else substring(0, end)
}

/**
 * A user's round avatar: their profile picture, or their initials on a colour from [colors] until
 * it loads and when it fails. [current] adds a check badge for the signed-in user, [locked] a lock
 * badge for a profile with a PIN (#299).
 */
@Composable
fun EphyraAvatar(
    name: String,
    imageUrl: String?,
    seed: UserId,
    size: Dp,
    colors: AvatarColors,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    contentDescription: String? = null,
    locked: Boolean = false,
) {
    val (container, onContainer) = colors.containers[avatarColorIndex(seed, colors.containers.size)]
    val semanticsModifier =
        if (contentDescription != null) {
            Modifier.semantics { this.contentDescription = contentDescription }
        } else {
            Modifier
        }

    Box(modifier = modifier.size(size).then(semanticsModifier)) {
        Box(
            modifier = Modifier.size(size).clip(CircleShape).background(container),
            contentAlignment = Alignment.Center,
        ) {
            val fontSize = with(LocalDensity.current) { (size * 0.42f).toSp() }
            // Once the picture is in, the initials go: a picture with transparent areas would
            // otherwise show them through.
            var pictureShown by remember(imageUrl) { mutableStateOf(false) }
            if (!pictureShown) {
                BasicText(
                    text = avatarInitials(name),
                    style =
                        TextStyle(
                            color = onContainer,
                            fontFamily = OutfitFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = fontSize,
                        ),
                )
            }
            if (imageUrl != null) {
                val crossfadeMillis = if (reducedMotion()) 0 else EphyraMotion.DURATION_MEDIUM
                val request =
                    ImageRequest.Builder(LocalContext.current)
                        .data(imageUrl)
                        .crossfade(crossfadeMillis)
                        .build()
                // The initials stay underneath: a failed or slow picture shows them, a loaded one
                // covers them.
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onSuccess = { pictureShown = true },
                    modifier = Modifier.size(size),
                )
            }
        }
        if (locked) {
            val badgeSize = maxOf(16.dp, size * 0.28f)
            val ring = 2.dp
            Box(
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .size(badgeSize + ring * 2)
                        .background(colors.badgeRing, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier.size(badgeSize).background(colors.lockContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_lock),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(colors.lockContent),
                        modifier = Modifier.size(badgeSize * 0.6f),
                    )
                }
            }
        }
        if (current) {
            val badgeSize = maxOf(16.dp, size * 0.28f)
            val ring = 2.dp
            Box(
                modifier =
                    Modifier.align(Alignment.BottomEnd)
                        .size(badgeSize + ring * 2)
                        .background(colors.badgeRing, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier.size(badgeSize).background(colors.badgeContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(colors.badgeContent),
                        modifier = Modifier.size(badgeSize * 0.6f),
                    )
                }
            }
        }
    }
}
