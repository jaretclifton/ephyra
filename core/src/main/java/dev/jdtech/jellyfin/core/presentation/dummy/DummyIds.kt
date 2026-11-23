package dev.jdtech.jellyfin.core.presentation.dummy

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toUserId
import java.util.UUID

// Ids for the previews' dummy items and users (#773): random, in the form a Jellyfin server's ids
// have. The one file of the dummies that names `UUID` (R10's allowlist).

/** A new random item id, for a preview. */
fun randomItemId(): ItemId = UUID.randomUUID().toString().toItemId()

/** A new random user id, for a preview. */
fun randomUserId(): UserId = UUID.randomUUID().toString().toUserId()
