package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.UserId
import java.util.UUID

// The one place where the Jellyfin SDK's `UUID` ids meet the domain's ids (#773), and the only
// place an id string is parsed as a `UUID`. Every id that comes from the SDK goes through
// `toItemId()` or `toUserId()`, which give the dashed lowercase form `UUID.toString()` gives (Room
// holds that text, see `Ids.kt` in domain); every id the SDK is given goes through `toUuid()`.
//
// An id that is not a UUID reaching the adapter is a programming error: every id the app holds
// for a Jellyfin server came from this adapter or from Room rows it wrote. `toUuid()` then throws
// `IllegalArgumentException` (from `UUID.fromString`) instead of mapping the id onto some other
// item, so the call fails the way any failed request does.

/** The domain's id of the item the SDK names by this `UUID`. */
internal fun UUID.toItemId(): ItemId = ItemId(toString())

/** The domain's id of the user the SDK names by this `UUID`. */
internal fun UUID.toUserId(): UserId = UserId(toString())

/**
 * The `UUID` the SDK wants for this item id.
 *
 * @throws IllegalArgumentException if the id is not a UUID (see the top of this file).
 */
internal fun ItemId.toUuid(): UUID = UUID.fromString(value)

/**
 * The `UUID` the SDK wants for this user id.
 *
 * @throws IllegalArgumentException if the id is not a UUID (see the top of this file).
 */
internal fun UserId.toUuid(): UUID = UUID.fromString(value)
