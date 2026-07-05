package com.anthonyla.paperize.core.di

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.data.database.PaperizeDatabase
import com.anthonyla.paperize.data.database.dao.AlbumDao
import com.anthonyla.paperize.data.database.dao.FolderDao
import com.anthonyla.paperize.data.database.dao.WallpaperCurrentDao
import com.anthonyla.paperize.data.database.dao.WallpaperDao
import com.anthonyla.paperize.data.database.dao.WallpaperQueueDao
import com.anthonyla.paperize.data.datastore.PreferencesManager
import com.anthonyla.paperize.data.repository.AlbumRepositoryImpl
import com.anthonyla.paperize.data.repository.SettingsRepositoryImpl
import com.anthonyla.paperize.data.repository.WallpaperRepositoryImpl
import com.anthonyla.paperize.domain.repository.AlbumRepository
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import androidx.sqlite.db.SupportSQLiteDatabase
import javax.inject.Singleton

/**
 * Hilt dependency injection module
 *
 * Provides all application-wide dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE wallpapers ADD COLUMN framingScale REAL NOT NULL DEFAULT 1.0")
            db.execSQL("ALTER TABLE wallpapers ADD COLUMN framingOffsetX REAL NOT NULL DEFAULT 0.0")
            db.execSQL("ALTER TABLE wallpapers ADD COLUMN framingOffsetY REAL NOT NULL DEFAULT 0.0")
        }
    }

    /**
     * Provide Room database
     */
    @Provides
    @Singleton
    fun providePaperizeDatabase(app: Application): PaperizeDatabase {
        return Room.databaseBuilder(
            app,
            PaperizeDatabase::class.java,
            Constants.DATABASE_NAME
        )
            .addMigrations(MIGRATION_4_5)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    /**
     * Provide DAOs
     */
    @Provides
    @Singleton
    fun provideAlbumDao(database: PaperizeDatabase): AlbumDao =
        database.albumDao()

    @Provides
    @Singleton
    fun provideWallpaperDao(database: PaperizeDatabase): WallpaperDao =
        database.wallpaperDao()

    @Provides
    @Singleton
    fun provideFolderDao(database: PaperizeDatabase): FolderDao =
        database.folderDao()

    @Provides
    @Singleton
    fun provideWallpaperQueueDao(database: PaperizeDatabase): WallpaperQueueDao =
        database.wallpaperQueueDao()

    @Provides
    @Singleton
    fun provideWallpaperCurrentDao(database: PaperizeDatabase): WallpaperCurrentDao =
        database.wallpaperCurrentDao()

    /**
     * Provide PreferencesManager
     */
    @Provides
    @Singleton
    fun providePreferencesManager(@ApplicationContext context: Context): PreferencesManager =
        PreferencesManager(context)

    /**
     * Provide Repositories
     */
    @Provides
    @Singleton
    fun provideAlbumRepository(
        @ApplicationContext context: Context,
        database: PaperizeDatabase,
        albumDao: AlbumDao,
        wallpaperDao: WallpaperDao,
        folderDao: FolderDao,
        wallpaperRepository: dagger.Lazy<WallpaperRepository>
    ): AlbumRepository = AlbumRepositoryImpl(context, database, albumDao, wallpaperDao, folderDao, wallpaperRepository)

    @Provides
    @Singleton
    fun provideWallpaperRepository(
        @ApplicationContext context: Context,
        wallpaperDao: WallpaperDao,
        wallpaperQueueDao: WallpaperQueueDao,
        wallpaperCurrentDao: WallpaperCurrentDao
    ): WallpaperRepository = WallpaperRepositoryImpl(context, wallpaperDao, wallpaperQueueDao, wallpaperCurrentDao)

    @Provides
    @Singleton
    fun provideSettingsRepository(
        preferencesManager: PreferencesManager
    ): SettingsRepository = SettingsRepositoryImpl(preferencesManager)
}
