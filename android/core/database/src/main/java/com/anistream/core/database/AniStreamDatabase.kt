package com.anistream.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.anistream.core.database.dao.AddonDao
import com.anistream.core.database.dao.DownloadDao
import com.anistream.core.database.dao.FavoriteDao
import com.anistream.core.database.dao.PlaylistDao
import com.anistream.core.database.dao.RecentSearchDao
import com.anistream.core.database.dao.RecommendationDao
import com.anistream.core.database.dao.WatchHistoryDao
import com.anistream.core.database.entity.AddonEntity
import com.anistream.core.database.entity.DownloadEntity
import com.anistream.core.database.entity.FavoriteEntity
import com.anistream.core.database.entity.PlaylistEntity
import com.anistream.core.database.entity.RecentSearchEntity
import com.anistream.core.database.entity.RecommendationEntity
import com.anistream.core.database.entity.WatchHistoryEntity

@Database(
    entities = [
        WatchHistoryEntity::class,
        FavoriteEntity::class,
        RecentSearchEntity::class,
        DownloadEntity::class,
        AddonEntity::class,
        PlaylistEntity::class,
        RecommendationEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AniStreamDatabase : RoomDatabase() {
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun downloadDao(): DownloadDao
    abstract fun addonDao(): AddonDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun recommendationDao(): RecommendationDao

    companion object {
        const val DATABASE_NAME = "anistream.db"

        /** v2: Movies & TV history/download columns (+ addon/playlist tables). Keeps existing users' history. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watch_history ADD COLUMN kind TEXT NOT NULL DEFAULT 'anime'")
                db.execSQL("ALTER TABLE watch_history ADD COLUMN season INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE watch_history ADD COLUMN mediaId TEXT")
                db.execSQL("ALTER TABLE watch_history ADD COLUMN subtitle TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN subtitle TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS `addons` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `manifestUrl` TEXT NOT NULL, `description` TEXT, `enabled` INTEGER NOT NULL, `builtIn` INTEGER NOT NULL, `resourcesCsv` TEXT NOT NULL, `typesCsv` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL)")
            }
        }

        /** v3: cached "For you" recommendations. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `recommendations` (`kind` TEXT NOT NULL, `id` TEXT NOT NULL, `title` TEXT NOT NULL, `posterUrl` TEXT, `year` INTEGER, `rating` REAL, `mediaType` TEXT, `because` TEXT, `position` INTEGER NOT NULL, PRIMARY KEY(`kind`, `id`))")
            }
        }

        /** v8: favourites are per profile, keep tombstones for sync, and carry what a card needs (kind, type, year). */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `favorites_new` (`animeSlug` TEXT NOT NULL, `malId` INTEGER, `animeTitle` TEXT NOT NULL, `posterUrl` TEXT, `score` REAL, `addedTimestamp` INTEGER NOT NULL, `totalEpisodes` INTEGER NOT NULL, `lastEpisodeWatched` INTEGER NOT NULL, `profileId` TEXT NOT NULL DEFAULT '', `deleted` INTEGER NOT NULL DEFAULT 0, `kind` TEXT NOT NULL DEFAULT 'anime', `mediaType` TEXT, `year` INTEGER, PRIMARY KEY(`animeSlug`, `profileId`))")
                db.execSQL("INSERT INTO favorites_new (animeSlug, malId, animeTitle, posterUrl, score, addedTimestamp, totalEpisodes, lastEpisodeWatched, kind) SELECT animeSlug, malId, animeTitle, posterUrl, score, addedTimestamp, totalEpisodes, lastEpisodeWatched, CASE WHEN animeSlug LIKE 'mb:%' OR animeSlug LIKE 'movie:%' OR animeSlug LIKE 'series:%' OR animeSlug GLOB 'tt[0-9]*' THEN 'media' ELSE 'anime' END FROM favorites")
                db.execSQL("DROP TABLE favorites")
                db.execSQL("ALTER TABLE favorites_new RENAME TO favorites")
            }
        }

        /** v7: episode length, so Continue watching can say "9 min left". */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watch_history ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v6: per-episode watch progress on history rows. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watch_history ADD COLUMN eps TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v5: downloads keep the poster so the list shows art. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN posterUrl TEXT")
            }
        }

        /** v4: history is per profile (PK animeSlug+profileId, local-only rows keep profileId '') and keeps tombstones for sync. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `watch_history_new` (`animeSlug` TEXT NOT NULL, `animeTitle` TEXT NOT NULL, `malId` INTEGER, `posterUrl` TEXT, `totalEpisodes` INTEGER NOT NULL, `lastEpisodeWatched` INTEGER NOT NULL, `watchProgress` REAL NOT NULL, `lastWatchedTimestamp` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, `anidbSlug` TEXT, `kind` TEXT NOT NULL DEFAULT 'anime', `season` INTEGER NOT NULL DEFAULT 0, `mediaId` TEXT, `subtitle` TEXT, `profileId` TEXT NOT NULL DEFAULT '', `deleted` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`animeSlug`, `profileId`))")
                db.execSQL("INSERT INTO watch_history_new (animeSlug, animeTitle, malId, posterUrl, totalEpisodes, lastEpisodeWatched, watchProgress, lastWatchedTimestamp, isFavorite, anidbSlug, kind, season, mediaId, subtitle) SELECT animeSlug, animeTitle, malId, posterUrl, totalEpisodes, lastEpisodeWatched, watchProgress, lastWatchedTimestamp, isFavorite, anidbSlug, kind, season, mediaId, subtitle FROM watch_history")
                db.execSQL("DROP TABLE watch_history")
                db.execSQL("ALTER TABLE watch_history_new RENAME TO watch_history")
            }
        }
    }
}
