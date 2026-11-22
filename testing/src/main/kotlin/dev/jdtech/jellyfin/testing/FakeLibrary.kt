package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidPerson
import dev.jdtech.jellyfin.models.FindroidSegment
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ServerInfo
import dev.jdtech.jellyfin.models.UserId

/**
 * What a [FakeMediaServer] and a [FakeServerConnector] hold (#771). [ContractLibrary] is the one
 * every implementation presents; a test that needs something else delegates to it and overrides
 * what differs (`object : FakeLibrary by ContractLibrary { override val items = … }`).
 *
 * Items carry only remote sources, as an adapter gives them: downloads are the repository's.
 */
interface FakeLibrary {
    /** What the server says about itself. */
    val server: ServerInfo

    /** The address the server answers at. */
    val address: String

    /** The user signed in, and the users the connector lets in, by name with their password. */
    val userId: UserId
    val users: Map<String, String>

    val libraries: List<FindroidCollection>

    /**
     * Movies, shows, seasons, episodes and box sets, with the user data they start with. Shows and
     * seasons list no seasons and episodes of their own, as an adapter gives them: the fake finds
     * them by `seriesId` and `seasonId`.
     */
    val items: List<FindroidItem>

    /** The items of each library and each box set, by its id. */
    val children: Map<ItemId, List<ItemId>>

    val people: List<FindroidPerson>

    /** The items a person is in, by the person's id. */
    val personItems: Map<ItemId, List<ItemId>>

    val segments: Map<ItemId, List<FindroidSegment>>

    /** Theme songs, by the id of their show or movie. */
    val themeSongs: Map<ItemId, String>
}
