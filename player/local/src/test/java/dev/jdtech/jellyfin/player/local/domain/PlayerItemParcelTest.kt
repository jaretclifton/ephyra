package dev.jdtech.jellyfin.player.local.domain

import android.os.Parcel
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773: [PlayerItem] carries item ids, which are value classes now, and a media source id (#902).
 * Parcelize does not write those by itself; its type parcelers write each id as its string, a
 * missing one as a null string.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerItemParcelTest {

    private fun roundTrip(item: PlayerItem): PlayerItem {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(item, 0)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            return parcel.readParcelable(PlayerItem::class.java.classLoader)!!
        } finally {
            parcel.recycle()
        }
    }

    private val episode =
        PlayerItem(
            name = "Obstacle Course",
            itemId = "0f8fad5b-d9cb-469f-a165-70867728950e".toItemId(),
            mediaSourceId = "0f8fad5bd9cb469fa16570867728950e".toMediaSourceId(),
            playbackPosition = 42L,
            seriesId = "7c9e6679-7425-40de-944b-e07fc1f90ae7".toItemId(),
            seasonId = "16fd2706-8baf-433b-82eb-8c7fada847da".toItemId(),
        )

    @Test
    fun `an item with all its ids comes back the same`() {
        assertEquals(episode, roundTrip(episode))
    }

    @Test
    fun `an item without a series or season comes back the same`() {
        val movie = episode.copy(seriesId = null, seasonId = null)
        assertEquals(movie, roundTrip(movie))
    }

    @Test
    fun `an id goes into the parcel as its string`() {
        val parcel = Parcel.obtain()
        try {
            episode.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            assertEquals("Obstacle Course", parcel.readString())
            assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", parcel.readString())
            // #902: the source id as it came, here the dashless form, as the String it was.
            assertEquals("0f8fad5bd9cb469fa16570867728950e", parcel.readString())
        } finally {
            parcel.recycle()
        }
    }
}
