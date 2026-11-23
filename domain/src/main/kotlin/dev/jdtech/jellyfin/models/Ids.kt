package dev.jdtech.jellyfin.models

import java.io.Serializable

// The ids of the domain (#773). An item or user id is an opaque string wrapped in its own type, so
// a user id cannot go where an item id belongs, and a server whose ids are not UUIDs (Emby) can use
// its own. `ServerId` and `MediaSourceId` are still aliases of the strings they are.
//
// The string form. For Jellyfin an item or user id is the dashed lowercase form `UUID.toString()`
// gives (`0f8fad5b-d9cb-469f-a165-70867728950e`): the Jellyfin adapter makes every id it hands out
// in that form (`SdkIds.kt` in server/jellyfin), also from the undashed form some answers use. Room
// stores that text in every id column (downloads, user data waiting to be sent, users), and
// navigation routes and saved back stacks carry it: it must not change, or nothing stored matches
// its item any more. Nothing outside the adapter parses an id.

/**
 * The id of anything in the catalog: movie, episode, season, show, person, library, collection. One
 * type for every kind of item (ADR 0002, Q4). Make one with [toItemId], read it with [asString].
 *
 * [toString] gives [value] too, so an id in a log line or a string template reads as it did when
 * ids were `UUID`s; code that needs the string form says [asString].
 *
 * It is [Serializable] as `UUID` was: where the id is boxed (a nullable or generic position),
 * Android stores it in a `Bundle` that way. Compose's lazy list keys and `rememberSaveable` values
 * must be Bundle types, and screens use item ids as both.
 */
@JvmInline
value class ItemId(val value: String) : Serializable {
    override fun toString(): String = value
}

/**
 * The id of a user on a server. Make one with [toUserId], read it with [asString]. [toString] and
 * [Serializable] as for [ItemId].
 */
@JvmInline
value class UserId(val value: String) : Serializable {
    override fun toString(): String = value
}

/** The id a server gives itself (`ServerInfo.id`), as the server sends it. */
typealias ServerId = String

/**
 * The id of one version (media source) of an item (`FindroidSource.id`), as the server sends it.
 */
typealias MediaSourceId = String

/**
 * The item id whose string form is this text (see the top of this file). It only wraps the text and
 * does not validate it: give it the stored or sent form, which for Jellyfin is the dashed lowercase
 * one the adapter produces.
 */
fun String.toItemId(): ItemId = ItemId(this)

/** The user id whose string form is this text; the same rules as [toItemId]. */
fun String.toUserId(): UserId = UserId(this)

private val JELLYFIN_ID_FORM = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/**
 * Whether this text has the form the Jellyfin adapter gives every id (dashed lowercase UUID). Only
 * for the few places that must tell an id the app wrote from other text found next to it: a folder
 * name in the artwork folders, a stored home-row entry, the hash behind a user's colour. Before the
 * flip (#773) they found out by parsing the text as a `UUID`; this keeps their answers. A second
 * server type with other ids needs its own rule there.
 */
fun String.looksLikeJellyfinId(): Boolean = JELLYFIN_ID_FORM.matches(this)

/**
 * The string form of the id (see the top of this file): what Room stores, routes carry and the
 * server is sent.
 */
fun ItemId.asString(): String = value

/** The string form of the id; see [ItemId.asString]. */
fun UserId.asString(): String = value
