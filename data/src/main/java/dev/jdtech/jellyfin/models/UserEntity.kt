package dev.jdtech.jellyfin.models

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey

/** The Room row of a [User] (#899). */
@Entity(
    tableName = "users",
    foreignKeys =
        [
            ForeignKey(
                entity = ServerEntity::class,
                parentColumns = arrayOf("id"),
                childColumns = arrayOf("serverId"),
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class UserEntity(
    @PrimaryKey val id: UserId,
    val name: String,
    @ColumnInfo(index = true) val serverId: ServerId,
    val accessToken: String? = null,
    /** Tag of the user's Jellyfin profile picture; null when they have none. */
    val primaryImageTag: String? = null,
    /** CE (#299): the profile PIN's PBKDF2 hash with [pinSalt]; null when it has none. */
    val pinHash: String? = null,
    val pinSalt: String? = null,
    @ColumnInfo(defaultValue = "1") val pinOnStart: Boolean = true,
    /** CE (#330): [ProfileLockType.value] of the lock. */
    @ColumnInfo(defaultValue = "pin") val lockType: String = ProfileLockType.PIN.value,
)

fun UserEntity.toUser(): User =
    User(
        id = id,
        name = name,
        serverId = serverId,
        accessToken = accessToken,
        primaryImageTag = primaryImageTag,
        pinHash = pinHash,
        pinSalt = pinSalt,
        pinOnStart = pinOnStart,
        lockType = lockType,
    )

fun User.toUserEntity(): UserEntity =
    UserEntity(
        id = id,
        name = name,
        serverId = serverId,
        accessToken = accessToken,
        primaryImageTag = primaryImageTag,
        pinHash = pinHash,
        pinSalt = pinSalt,
        pinOnStart = pinOnStart,
        lockType = lockType,
    )
