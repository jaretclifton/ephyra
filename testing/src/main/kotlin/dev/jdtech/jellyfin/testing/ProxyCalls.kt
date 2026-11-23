package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.UserId
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

// Helpers for the test fakes built with `java.lang.reflect.Proxy` (#773). `ItemId` and `UserId` are
// value classes, and Kotlin compiles a function that takes one to a JVM method with a mangled name
// (`getMovie(ItemId)` is `getMovie-<hash>`) whose parameter is the plain `String`. A fake that
// switches on `method.name`, casts `args[0] as ItemId` or answers with an `ItemId` where the JVM
// method returns a `String` then silently stops matching or throws.

/**
 * A fake of [interfaces] built with `java.lang.reflect.Proxy`, as `Proxy.newProxyInstance` builds
 * it, except that an [ItemId] or [UserId] the [handler] answers with goes back as the JVM method
 * wants it: a function declared to return a value class returns its `String` on the JVM. (A suspend
 * function returns it boxed, as the handler gives it.)
 */
fun fakeProxy(
    loader: ClassLoader?,
    interfaces: Array<out Class<*>>,
    handler: InvocationHandler,
): Any =
    Proxy.newProxyInstance(loader, interfaces) { proxy, method, args ->
        val answer = handler.invoke(proxy, method, args)
        if (method.returnType != String::class.java) answer
        else
            when (answer) {
                is ItemId -> answer.value
                is UserId -> answer.value
                else -> answer
            }
    }

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
