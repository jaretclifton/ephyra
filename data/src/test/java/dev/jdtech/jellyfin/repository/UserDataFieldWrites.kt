package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.UserId

// #898: the single-field user-data writes the tests set rows up with. Production code writes user
// data only through ServerDatabaseDao.updatePlayState with a PlayState rule, so these left the DAO.
// Same names and parameters as the DAO queries had, and the same effect: one field of an existing
// row, nothing without a row.

internal suspend fun ServerDatabaseDao.setFavorite(
    userId: UserId,
    itemId: ItemId,
    favorite: Boolean,
) = updatePlayState(userId, itemId) { it.copy(favorite = favorite) }

internal suspend fun ServerDatabaseDao.setLastPlayedDate(
    userId: UserId,
    itemId: ItemId,
    lastPlayedDate: Long?,
) = updatePlayState(userId, itemId) { it.copy(lastPlayedMillis = lastPlayedDate) }

internal suspend fun ServerDatabaseDao.setPlaybackPositionTicks(
    itemId: ItemId,
    userId: UserId,
    playbackPositionTicks: Long,
) = updatePlayState(userId, itemId) { it.copy(positionTicks = playbackPositionTicks) }
