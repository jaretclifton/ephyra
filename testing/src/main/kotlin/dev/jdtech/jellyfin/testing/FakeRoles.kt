package dev.jdtech.jellyfin.testing

import java.lang.reflect.InvocationHandler
import kotlin.reflect.KClass

/**
 * #967: one Proxy fake (see [fakeProxy]) that is each of [roles] at once, for a test of a class
 * that takes several roles of the repository (`ItemCatalog`, `ItemLists`, `UserDataUpdates`, …) and
 * answers them from one [handler], which switches on [kotlinName] as before. [role] hands it out as
 * one of them:
 * ```
 * val repository = FakeRoles(ItemCatalog::class, UserDataUpdates::class) { _, method, args -> … }
 * ShowViewModel(catalog = repository.role(), userData = repository.role(), …)
 * ```
 *
 * A call to a role that was not listed does not compile, so a test names what the class under test
 * uses. A test of a class that takes one role builds its fake with [fakeRole].
 */
class FakeRoles(vararg roles: KClass<*>, handler: InvocationHandler) {
    init {
        require(roles.isNotEmpty()) { "FakeRoles needs at least one role" }
    }

    /** The proxy itself: an instance of every role. */
    val proxy: Any =
        fakeProxy(roles.first().java.classLoader, roles.map { it.java }.toTypedArray(), handler)

    /** The fake as the role [T]; fails when [T] is not one of the roles it was built with. */
    inline fun <reified T : Any> role(): T =
        proxy as? T ?: throw IllegalArgumentException("Not built as a ${T::class.simpleName}")
}

/** #967: a Proxy fake of one role [T] (see [fakeProxy] and [FakeRoles]). */
inline fun <reified T : Any> fakeRole(handler: InvocationHandler): T =
    fakeProxy(T::class.java.classLoader, arrayOf(T::class.java), handler) as T
