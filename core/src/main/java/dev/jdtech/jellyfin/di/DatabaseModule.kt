package dev.jdtech.jellyfin.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.database.MIGRATION_6_7
import dev.jdtech.jellyfin.database.RoomPendingUserDataStore
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.profilelock.ProfileLock
import dev.jdtech.jellyfin.repository.PendingUserDataStore
import javax.inject.Singleton

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

    // CE (#299): one per process, so an unlocked profile stays unlocked across screens.
    @Singleton
    @Provides
    fun provideProfileLock(database: ServerDatabaseDao): ProfileLock = ProfileLock(database)
}
