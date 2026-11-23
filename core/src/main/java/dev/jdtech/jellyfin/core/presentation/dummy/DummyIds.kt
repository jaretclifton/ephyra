package dev.jdtech.jellyfin.core.presentation.dummy

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toUserId
import kotlin.uuid.Uuid

// Ids for the previews' dummy items and users (#773): random, in the form a Jellyfin server's ids
// have, made with Kotlin's own `Uuid` so no file of the dummies names `java.util.UUID` (R10).

/** A new random item id, for a preview. */
fun randomItemId(): ItemId = Uuid.random().toString().toItemId()

/** A new random user id, for a preview. */
fun randomUserId(): UserId = Uuid.random().toString().toUserId()
