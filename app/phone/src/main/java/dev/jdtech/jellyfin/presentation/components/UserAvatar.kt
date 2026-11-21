package dev.jdtech.jellyfin.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.presentation.dummy.dummyUser
import dev.jdtech.jellyfin.core.presentation.ephyra.AvatarColors
import dev.jdtech.jellyfin.core.presentation.ephyra.EphyraAvatar
import dev.jdtech.jellyfin.core.presentation.session.LocalServerSession
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.hasPin
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import kotlinx.coroutines.flow.emptyFlow

/**
 * A user's avatar in the phone theme. The picture comes from the server of [LocalServerSession],
 * the one the user belongs to.
 */
@Composable
fun UserAvatar(
    user: User,
    size: Dp,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    /** CE (#299): show the lock badge when the profile has a PIN. */
    showLock: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val colors =
        remember(scheme) {
            AvatarColors(
                containers =
                    listOf(
                        scheme.secondaryContainer to scheme.onSecondaryContainer,
                        scheme.tertiaryContainer to scheme.onTertiaryContainer,
                        scheme.primaryContainer to scheme.onPrimaryContainer,
                        scheme.surfaceContainerHighest to scheme.onSurfaceVariant,
                    ),
                badgeContainer = scheme.primary,
                badgeContent = scheme.onPrimary,
                badgeRing = scheme.surface,
                lockContainer = scheme.surfaceContainerHigh,
                lockContent = scheme.onSurfaceVariant,
            )
        }
    val session = LocalServerSession.current
    EphyraAvatar(
        name = user.name,
        imageUrl = user.primaryImageTag?.let { session.userImageUrl(user.id, it) },
        seed = user.id,
        size = size,
        colors = colors,
        modifier = modifier,
        current = current,
        locked = showLock && user.hasPin,
    )
}

@Preview
@Composable
private fun UserAvatarInitialsPreview() {
    FindroidTheme { UserAvatar(user = dummyUser, size = 56.dp) }
}

@Preview
@Composable
private fun UserAvatarCurrentPreview() {
    FindroidTheme { UserAvatar(user = dummyUser, size = 56.dp, current = true) }
}

@Preview
@Composable
private fun UserAvatarPicturePreview() {
    val session =
        object : ServerSession {
            override val baseUrl = "https://demo.example"
            override val userId: UserId? = null
            override val serverReturned = emptyFlow<Unit>()

            override fun userImageUrl(userId: UserId, tag: String) =
                "$baseUrl/Users/$userId/Images/Primary?tag=$tag"
        }
    CompositionLocalProvider(LocalServerSession provides session) {
        FindroidTheme {
            UserAvatar(user = dummyUser.copy(primaryImageTag = "tag"), size = 56.dp, current = true)
        }
    }
}
