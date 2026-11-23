package dev.jdtech.jellyfin.models

import java.io.Serializable
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable as KSerializable

// The ids of the domain (#773, #902). An item, user, server or media source id is an opaque string
// wrapped in its own type, so one kind of id cannot go where another belongs, and a server whose
// ids are not UUIDs (Emby) can use its own.
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
 *
 * It is also kotlinx-serializable ([KSerializable]), as the plain string it wraps: an inline value
 * class over a `String` is encoded as that string. So a navigation route, which the saved back
 * stack stores, or the Cast receiver's custom data can hold an id and still write and read the text
 * it did when the field was a `String` (`RouteIdsSerializationTest` in app/phone and app/tv,
 * `CastCustomDataTest`).
 */
@KSerializable
@JvmInline
value class ItemId(val value: String) : Serializable {
    override fun toString(): String = value
}

/**
 * The id of a user on a server. Make one with [toUserId], read it with [asString]. [toString],
 * [Serializable] and [KSerializable] as for [ItemId].
 */
@KSerializable
@JvmInline
value class UserId(val value: String) : Serializable {
    override fun toString(): String = value
}

/**
 * The id a server gives itself (`ServerInfo.id`), exactly as the server sends it (#902). It names
 * the server in Room (the servers, users and downloads), in the current server preference and in
 * the saved session, so its text never changes form. Make one with [toServerId], read it with
 * [asString]. [toString], [Serializable] and [KSerializable] as for [ItemId].
 */
@KSerializable
@JvmInline
value class ServerId(val value: String) : Serializable {
    override fun toString(): String = value
}

/**
 * The id of one version (media source) of an item (`ItemSource.id`), exactly as the server sends it
 * (#902). For Jellyfin it is a UUID, dashed or not depending on the answer it came in: the text is
 * kept as it came, never normalised, because Room (sources, media streams, trickplay), the player
 * and the receiver match it as text. Make one with [toMediaSourceId], read it with [asString].
 * [toString], [Serializable] and [KSerializable] as for [ItemId].
 */
@KSerializable
@JvmInline
value class MediaSourceId(val value: String) : Serializable {
    override fun toString(): String = value
}

/**
 * The id the app gives each server address it saves (Room's `serverAddresses.id` and
 * `servers.currentServerAddressId`), #918. It is the app's own id, not the server's: [random] makes
 * a new one in the dashed lowercase form the `UUID` column converter stored before, and a stored id
 * reads back as the text it is. Nothing parses it. Make one from stored text with
 * [toServerAddressId], read it with [asString]. [toString] and [Serializable] as for [ItemId] (the
 * address list keys its rows by it).
 */
@JvmInline
value class ServerAddressId(val value: String) : Serializable {
    override fun toString(): String = value

    companion object {
        /**
         * A new random id, in the form `UUID.randomUUID().toString()` gave (see [randomIdText]).
         */
        fun random(): ServerAddressId = ServerAddressId(randomIdText())
    }
}

/**
 * The id the downloader gives each media stream (an external subtitle) it stores (Room's
 * `mediastreams.id`), #918. The app's own id, made by [random] in the same form as
 * [ServerAddressId]; it is also part of the stream's file name. Make one from stored text with
 * [toMediaStreamId], read it with [asString]. [toString] and [Serializable] as for [ItemId].
 */
@JvmInline
value class MediaStreamId(val value: String) : Serializable {
    override fun toString(): String = value

    companion object {
        /**
         * A new random id, in the form `UUID.randomUUID().toString()` gave (see [randomIdText]).
         */
        fun random(): MediaStreamId = MediaStreamId(randomIdText())
    }
}

/**
 * A random version 4 UUID as text, made with Kotlin's own [Uuid]: 36 characters, lowercase hex,
 * dashed 8-4-4-4-12, the form `java.util.UUID.toString()` gives and the old Room converter stored.
 */
internal fun randomIdText(): String = Uuid.random().toString()

/**
 * The item id whose string form is this text (see the top of this file). It only wraps the text and
 * does not validate it: give it the stored or sent form, which for Jellyfin is the dashed lowercase
 * one the adapter produces.
 */
fun String.toItemId(): ItemId = ItemId(this)

/** The user id whose string form is this text; the same rules as [toItemId]. */
fun String.toUserId(): UserId = UserId(this)

/** The server id whose string form is this text. It only wraps the text, as [toItemId]. */
fun String.toServerId(): ServerId = ServerId(this)

/** The media source id whose string form is this text. It only wraps the text, as [toItemId]. */
fun String.toMediaSourceId(): MediaSourceId = MediaSourceId(this)

/** The server address id whose stored text is this. It only wraps the text, as [toItemId]. */
fun String.toServerAddressId(): ServerAddressId = ServerAddressId(this)

/** The media stream id whose stored text is this. It only wraps the text, as [toItemId]. */
fun String.toMediaStreamId(): MediaStreamId = MediaStreamId(this)

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

/** The string form of the id, as the server sent it; see [ItemId.asString]. */
fun ServerId.asString(): String = value

/** The string form of the id, as the server sent it; see [ItemId.asString]. */
fun MediaSourceId.asString(): String = value

/** The text of the id, as Room stores it; see [ItemId.asString]. */
fun ServerAddressId.asString(): String = value

/** The text of the id, as Room stores it and the stream's file name holds it. */
fun MediaStreamId.asString(): String = value
