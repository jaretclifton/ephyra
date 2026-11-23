package dev.jdtech.jellyfin.data.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.database.MIGRATION_6_7
import dev.jdtech.jellyfin.database.RoomDownloadStore
import dev.jdtech.jellyfin.database.RoomPendingUserDataStore
import dev.jdtech.jellyfin.database.RoomServerStore
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.profilelock.ProfileLock
import dev.jdtech.jellyfin.repository.DownloadStore
import dev.jdtech.jellyfin.repository.PendingUserDataStore
import dev.jdtech.jellyfin.repository.ServerStore
import javax.inject.Singleton

/**
 * The Room database and the store ports over it (#899). In `data` since part C1, like
 * `JellyfinServerModule` in `server/jellyfin`: only the apps depend on this module, so only they
 * see the DAO.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Singleton
    @Provides
    fun provideServerDatabaseDao(@ApplicationContext app: Context): ServerDatabaseDao {
        return Room.databaseBuilder(app.applicationContext, ServerDatabase::class.java, "servers")
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(MIGRATION_6_7)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
            .getServerDatabaseDao()
    }

    /** #899: what the user data sync reads and writes in Room. */
    @Singleton
    @Provides
    fun providePendingUserDataStore(database: ServerDatabaseDao): PendingUserDataStore =
        RoomPendingUserDataStore(database)

    /** #899: the saved servers, addresses and users. */
    @Singleton
    @Provides
    fun provideServerStore(database: ServerDatabaseDao): ServerStore = RoomServerStore(database)

    // CE (#299): one per process, so an unlocked profile stays unlocked across screens.
    @Singleton
    @Provides
    fun provideProfileLock(store: ServerStore): ProfileLock = ProfileLock(store)

    // #899: the download rows, for the downloader, the queue, Smart Downloads and the screens.
    @Singleton
    @Provides
    fun provideDownloadStore(database: ServerDatabaseDao): DownloadStore =
        RoomDownloadStore(database)
}
