package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.UserId
import java.util.UUID

// The one place where the Jellyfin SDK's `UUID` ids meet the domain's ids (#773). Every id that
// comes from the SDK goes through `toItemId()` or `toUserId()`, every id the SDK is given through
// `toUuid()`. While `ItemId` and `UserId` are aliases of `UUID` these do nothing; when they become
// value classes over the dashed lowercase string (see `Ids.kt` in domain), only this file changes.

/** The domain's id of the item the SDK names by this `UUID`. */
internal fun UUID.toItemId(): ItemId = this

/** The domain's id of the user the SDK names by this `UUID`. */
internal fun UUID.toUserId(): UserId = this

/**
 * The `UUID` the SDK wants for this id. One function for item and user ids while both are aliases
 * of `UUID` (two would clash); at the flip each gets its own.
 */
internal fun ItemId.toUuid(): UUID = this
