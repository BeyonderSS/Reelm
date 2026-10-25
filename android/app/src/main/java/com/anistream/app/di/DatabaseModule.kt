package com.anistream.app.di

import android.content.Context
import androidx.room.Room
import com.anistream.core.database.AniStreamDatabase
import com.anistream.core.database.dao.AddonDao
import com.anistream.core.database.dao.DownloadDao
import com.anistream.core.database.dao.FavoriteDao
import com.anistream.core.database.dao.PlaylistDao
import com.anistream.core.database.dao.RecentSearchDao
import com.anistream.core.database.dao.WatchHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): AniStreamDatabase {
        return Room.databaseBuilder(
            context,
            AniStreamDatabase::class.java,
            AniStreamDatabase.DATABASE_NAME
        ).addMigrations(AniStreamDatabase.MIGRATION_1_2, AniStreamDatabase.MIGRATION_2_3, AniStreamDatabase.MIGRATION_3_4, AniStreamDatabase.MIGRATION_4_5, AniStreamDatabase.MIGRATION_5_6, AniStreamDatabase.MIGRATION_6_7, AniStreamDatabase.MIGRATION_7_8, AniStreamDatabase.MIGRATION_8_9).build()
    }

    @Provides
    fun provideWatchHistoryDao(db: AniStreamDatabase): WatchHistoryDao = db.watchHistoryDao()

    @Provides
    fun provideFavoriteDao(db: AniStreamDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun provideRecentSearchDao(db: AniStreamDatabase): RecentSearchDao = db.recentSearchDao()

    @Provides
    fun provideDownloadDao(db: AniStreamDatabase): DownloadDao = db.downloadDao()

    @Provides
    fun provideAddonDao(db: AniStreamDatabase): AddonDao = db.addonDao()

    @Provides
    fun providePlaylistDao(db: AniStreamDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideRecommendationDao(db: AniStreamDatabase): com.anistream.core.database.dao.RecommendationDao = db.recommendationDao()
}
