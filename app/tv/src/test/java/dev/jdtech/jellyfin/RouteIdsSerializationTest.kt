package dev.jdtech.jellyfin

import android.app.Application
import android.os.Bundle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The TV routes carry `ItemId` and `MediaSourceId` where they carried the id's `String` (#918).
 * `rememberNavBackStack` (NavigationRoot) saves the back stack with
 * `NavBackStackSerializer(NavKeySerializer())`, and one an older version saved has to come back
 * after the update. As the phone app's test of the same name: each route against a copy of itself
 * with `String` ids.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RouteIdsSerializationTest {

    @Test
    fun library() =
        check(
            LibraryRoute(ITEM.toItemId(), "Films", CollectionType.Movies),
            OldLibraryRoute(ITEM, "Films", CollectionType.Movies),
        )

    @Test
    fun collection() =
        check(CollectionRoute(ITEM.toItemId(), "Set"), OldCollectionRoute(ITEM, "Set"))

    @Test fun movie() = check(MovieRoute(ITEM.toItemId()), OldMovieRoute(ITEM))

    @Test fun show() = check(ShowRoute(ITEM.toItemId()), OldShowRoute(ITEM))

    @Test
    fun season() {
        check(SeasonRoute(ITEM.toItemId()), OldSeasonRoute(ITEM))
        check(SeasonRoute(ITEM.toItemId(), SERIES.toItemId()), OldSeasonRoute(ITEM, SERIES))
    }

    @Test fun episode() = check(EpisodeRoute(ITEM.toItemId()), OldEpisodeRoute(ITEM))

    @Test fun person() = check(PersonRoute(ITEM.toItemId()), OldPersonRoute(ITEM))

    @Test
    fun player() {
        check(PlayerRoute(ITEM.toItemId(), "Movie"), OldPlayerRoute(ITEM, "Movie"))
        check(
            PlayerRoute(
                ITEM.toItemId(),
                "Episode",
                mediaSourceId = SOURCE.toMediaSourceId(),
                startFromBeginning = true,
                shuffle = true,
            ),
            OldPlayerRoute(ITEM, "Episode", SOURCE, startFromBeginning = true, shuffle = true),
        )
    }

    // The routes as they were before #918, every id a String.
    @Serializable
    private data class OldLibraryRoute(
        val libraryId: String,
        val libraryName: String,
        val libraryType: CollectionType,
    )

    @Serializable
    private data class OldCollectionRoute(val collectionId: String, val collectionName: String)

    @Serializable private data class OldMovieRoute(val itemId: String)

    @Serializable private data class OldShowRoute(val itemId: String)

    @Serializable
    private data class OldSeasonRoute(val seasonId: String, val seriesId: String? = null)

    @Serializable private data class OldEpisodeRoute(val episodeId: String)

    @Serializable private data class OldPersonRoute(val personId: String)

    @Serializable
    private data class OldPlayerRoute(
        val itemId: String,
        val itemKind: String,
        val mediaSourceId: String? = null,
        val startFromBeginning: Boolean = false,
        val shuffle: Boolean = false,
    )

    private inline fun <reified New : NavKey, reified Old : Any> check(new: New, old: Old) =
        RouteSavedState.check(new, serializer<New>(), old, serializer<Old>())

    private companion object {
        const val ITEM = "0f8fad5b-d9cb-469f-a165-70867728950e"
        const val SERIES = "31e47044-6c2a-4f6e-9a3b-6d1f0e0b7a11"
        const val SOURCE = "8f0862286a92020d0cb735d734765afe"
    }
}

/** The checks of [RouteIdsSerializationTest], a copy of the phone app's. */
internal object RouteSavedState {

    fun <New : NavKey, Old : Any> check(
        new: New,
        newSerializer: KSerializer<New>,
        old: Old,
        oldSerializer: KSerializer<Old>,
    ) {
        val oldState = encodeToSavedState(oldSerializer, old)
        val newState = encodeToSavedState(newSerializer, new)
        assertEquals(contents(oldState), contents(newState))
        assertEquals(new, decodeFromSavedState(newSerializer, oldState))
        assertEquals(old.toString().removePrefix("Old"), new.toString())

        // The whole back stack, through the serializer rememberNavBackStack uses. It holds the
        // route's class name, which is unchanged, and the route's serializer output: put the old
        // route's output in its place, as an older version saved it, and the route comes back.
        val backStack = NavBackStackSerializer(NavKeySerializer<NavKey>())
        val stack = encodeToSavedState(backStack, NavBackStack<NavKey>(new))
        var places = 0
        val oldStack =
            replaced(stack, contents(newState)) {
                places++
                oldState
            }
        assertEquals("the route's places in the saved back stack", 1, places)
        assertEquals(listOf<NavKey>(new), decodeFromSavedState(backStack, oldStack).toList())
        assertEquals(listOf<NavKey>(new), decodeFromSavedState(backStack, stack).toList())
    }

    /** [state] with each nested state whose contents are [target] replaced by [by]. */
    private fun replaced(state: Bundle, target: Map<String, Any?>, by: () -> Bundle): Bundle {
        val out = Bundle()
        for (key in state.keySet()) {
            @Suppress("DEPRECATION")
            when (val value = state.get(key)) {
                is Bundle ->
                    out.putBundle(
                        key,
                        if (contents(value) == target) by() else replaced(value, target, by),
                    )
                is String -> out.putString(key, value)
                is Int -> out.putInt(key, value)
                else -> error("Unexpected value in the saved back stack: $key = $value")
            }
        }
        return out
    }

    private fun contents(state: Bundle): Map<String, Any?> =
        state.keySet().associateWith { key ->
            @Suppress("DEPRECATION") val value = state.get(key)
            if (value is Bundle) contents(value) else value
        }
}
