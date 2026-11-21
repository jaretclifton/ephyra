package dev.jdtech.jellyfin.models

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class IdsTest {

    @Test
    fun `the id aliases stand for their underlying types`() {
        val uuid = UUID.randomUUID()
        val itemId: ItemId = uuid
        val userId: UserId = uuid
        val serverId: ServerId = "server"
        val mediaSourceId: MediaSourceId = "source"

        assertEquals<UUID>(uuid, itemId)
        assertEquals<UUID>(uuid, userId)
        assertEquals<String>("server", serverId)
        assertEquals<String>("source", mediaSourceId)
    }
}
