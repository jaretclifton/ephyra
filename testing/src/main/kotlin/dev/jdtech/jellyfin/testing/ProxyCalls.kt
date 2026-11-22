package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.UserId
import java.lang.reflect.Method

// Helpers for the test fakes built with `java.lang.reflect.Proxy` (#773). `ItemId` and `UserId` are
// value classes, and Kotlin compiles a function that takes one to a JVM method with a mangled name
// (`getMovie(ItemId)` is `getMovie-<hash>`) whose parameter is the plain `String`. A fake that
// switches on `method.name` or casts `args[0] as ItemId` then silently stops matching or throws.

/**
 * The Kotlin name of the [Method] a Proxy fake is called with: the JVM name without its mangling.
 */
val Method.kotlinName: String
    get() = name.substringBefore('-')

/**
 * This argument of a Proxy call as an [ItemId]: the JVM passes a value class parameter as its
 * `String`, and a boxed one (a generic or nullable position) as the [ItemId] itself.
 */
fun Any?.asItemId(): ItemId =
    when (this) {
        is ItemId -> this
        is String -> ItemId(this)
        else -> throw IllegalArgumentException("Not an item id: $this")
    }

/** [asItemId] for an argument that may be null. */
fun Any?.asItemIdOrNull(): ItemId? = this?.asItemId()

/** This argument of a Proxy call as a [UserId]; see [asItemId]. */
fun Any?.asUserId(): UserId =
    when (this) {
        is UserId -> this
        is String -> UserId(this)
        else -> throw IllegalArgumentException("Not a user id: $this")
    }

/** [asUserId] for an argument that may be null. */
fun Any?.asUserIdOrNull(): UserId? = this?.asUserId()
