package dev.jdtech.jellyfin.models

import java.util.UUID

// The ids of the domain (#773). Today the item and user ids are aliases of `java.util.UUID`; they
// become `@JvmInline value class ItemId(val value: String)` and `UserId` in the flip (#773, T2),
// and `ServerId` and `MediaSourceId` value classes over the strings they are now. Code outside the
// Jellyfin adapter makes and reads ids only through the functions below, which keep their names
// and meaning after the flip, so the flip changes this file and not its callers.
//
// The string form. For Jellyfin an item or user id is the dashed lowercase form `UUID.toString()`
// gives (`0f8fad5b-d9cb-469f-a165-70867728950e`). Room stores that text in every id column
// (downloads, user data waiting to be sent, users), and navigation routes and saved back stacks
// carry it: it must not change, or nothing stored matches its item any more. The adapter parses
// what the server sends through `UUID`, which also turns the undashed form some answers use into
// this one.

/**
 * The id of anything in the catalog: movie, episode, season, show, person, library, collection. One
 * type for every kind of item (ADR 0002, Q4). Make one with [toItemId], read it with [asString].
 */
typealias ItemId = UUID

/** The id of a user on a server. Make one with [toUserId], read it with [asString]. */
typealias UserId = UUID

/** The id a server gives itself (`ServerInfo.id`), as the server sends it. */
typealias ServerId = String

/**
 * The id of one version (media source) of an item (`FindroidSource.id`), as the server sends it.
 */
typealias MediaSourceId = String

/**
 * The item id whose string form is this text (see the top of this file).
 *
 * Today it parses the text as a `UUID`, so it throws [IllegalArgumentException] on text that is not
 * one, and a non-canonical form (upper case) comes back in the canonical one. After the flip it
 * only wraps the text: give it the stored or sent form, and do not rely on it to validate.
 */
fun String.toItemId(): ItemId = UUID.fromString(this)

/** The user id whose string form is this text; the same rules as [toItemId]. */
fun String.toUserId(): UserId = UUID.fromString(this)

/**
 * The string form of the id (see the top of this file): what Room stores, routes carry and the
 * server is sent. Never `toString()`, which stops giving this form when the id becomes a value
 * class.
 *
 * One function for [ItemId] and [UserId] alike while both are aliases of the same `UUID` (two would
 * clash); at the flip each class gets its own, and callers do not change.
 */
fun ItemId.asString(): String = toString()
