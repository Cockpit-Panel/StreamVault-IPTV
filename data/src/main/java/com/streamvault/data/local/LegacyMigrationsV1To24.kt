@file:Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE", "TYPE_INTERSECTION_AS_REIFIED")

package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/** Historical foundation migrations (v1 through v24). Executable bodies live here, outside the Room schema declaration. */
internal object LegacyMigrationsV1To24 {
    val MIGRATION_1_2 = object : Migration(1, 2) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    // Schema was identical between v1 and v2; nothing to alter.
                }
            }
    
            /**
             * Migration 2 → 3: added parental-control protection columns.
             */

    val MIGRATION_2_3 = object : Migration(2, 3) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE categories ADD COLUMN is_adult INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE categories ADD COLUMN is_user_protected INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE channels ADD COLUMN is_adult INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE channels ADD COLUMN is_user_protected INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE movies ADD COLUMN is_adult INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE movies ADD COLUMN is_user_protected INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE series ADD COLUMN is_adult INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE series ADD COLUMN is_user_protected INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE episodes ADD COLUMN is_adult INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE episodes ADD COLUMN is_user_protected INTEGER NOT NULL DEFAULT 0")
                }
            }

    val MIGRATION_3_4 = object : Migration(3, 4) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE channels ADD COLUMN catchUpSource TEXT")
                    // 1. Create playback_history table
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS playback_history (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            content_id INTEGER NOT NULL,
                            content_type TEXT NOT NULL,
                            provider_id INTEGER NOT NULL,
                            title TEXT NOT NULL DEFAULT '',
                            poster_url TEXT,
                            stream_url TEXT NOT NULL DEFAULT '',
                            resume_position_ms INTEGER NOT NULL DEFAULT 0,
                            total_duration_ms INTEGER NOT NULL DEFAULT 0,
                            last_watched_at INTEGER NOT NULL DEFAULT 0,
                            watch_count INTEGER NOT NULL DEFAULT 1,
                            series_id INTEGER,
                            season_number INTEGER,
                            episode_number INTEGER
                        )
                    """)
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_history_unique ON playback_history(content_id, content_type, provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS idx_history_last_watched ON playback_history(last_watched_at DESC)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS idx_history_provider ON playback_history(provider_id)")
                    
                    // 2. Create sync_metadata table
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS sync_metadata (
                            provider_id INTEGER PRIMARY KEY NOT NULL,
                            last_live_sync INTEGER NOT NULL DEFAULT 0,
                            last_movie_sync INTEGER NOT NULL DEFAULT 0,
                            last_series_sync INTEGER NOT NULL DEFAULT 0,
                            last_epg_sync INTEGER NOT NULL DEFAULT 0,
                            live_count INTEGER NOT NULL DEFAULT 0,
                            movie_count INTEGER NOT NULL DEFAULT 0,
                            series_count INTEGER NOT NULL DEFAULT 0,
                            epg_count INTEGER NOT NULL DEFAULT 0,
                            last_sync_status TEXT NOT NULL DEFAULT 'NONE'
                        )
                    """)
                    
                    // 3. Migrate existing watch progress to history
                    database.execSQL("""
                        INSERT OR IGNORE INTO playback_history (content_id, content_type, provider_id, title, resume_position_ms, last_watched_at)
                        SELECT id, 'MOVIE', provider_id, name, watch_progress, last_watched_at
                        FROM movies WHERE watch_progress > 0
                    """)
                    database.execSQL("""
                        INSERT OR IGNORE INTO playback_history (content_id, content_type, provider_id, title, resume_position_ms, last_watched_at, series_id, season_number, episode_number)
                        SELECT id, 'SERIES_EPISODE', provider_id, title, watch_progress, last_watched_at, series_id, season_number, episode_number
                        FROM episodes WHERE watch_progress > 0
                    """)
                }
            }

    val MIGRATION_4_5 = object : Migration(4, 5) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    // Note: Room auto-generates index names as 'index_tableName_columnNames'
                    // Channels
                    database.execSQL("DROP INDEX IF EXISTS index_channels_category_id")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_channels_provider_id_category_id ON channels(provider_id, category_id)")
                    
                    // Movies
                    database.execSQL("DROP INDEX IF EXISTS index_movies_category_id")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_movies_provider_id_category_id ON movies(provider_id, category_id)")
                    
                    // Series
                    database.execSQL("DROP INDEX IF EXISTS index_series_category_id")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_series_provider_id_category_id ON series(provider_id, category_id)")
                    
                    // Favorites
                    database.execSQL("DROP INDEX IF EXISTS index_favorites_group_id")
                    database.execSQL("DROP INDEX IF EXISTS index_favorites_position")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_content_type_group_id ON favorites(content_type, group_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_group_id_position ON favorites(group_id, position)")
                }
            }

    val MIGRATION_5_6 = object : Migration(5, 6) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE virtual_groups ADD COLUMN content_type TEXT NOT NULL DEFAULT 'LIVE'")
                }
            }

    val MIGRATION_6_7 = object : Migration(6, 7) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE channels ADD COLUMN logical_group_id TEXT NOT NULL DEFAULT ''")
                    database.execSQL("ALTER TABLE channels ADD COLUMN error_count INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_channels_logical_group_id ON channels(logical_group_id)")
                }
            }

    val MIGRATION_7_8 = object : Migration(7, 8) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE programs ADD COLUMN provider_id INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("DROP INDEX IF EXISTS index_programs_channel_id")
                    database.execSQL("DROP INDEX IF EXISTS index_programs_channel_id_start_time")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_programs_provider_id ON programs(provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_programs_provider_id_channel_id ON programs(provider_id, channel_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_programs_provider_id_channel_id_start_time ON programs(provider_id, channel_id, start_time)")
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_programs_provider_id_channel_id_start_time_end_time " +
                            "ON programs(provider_id, channel_id, start_time, end_time)"
                    )
                }
            }

    val MIGRATION_8_9 = object : Migration(8, 9) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    // Rebuild media tables to normalize legacy local IDs and keep only provider-scoped remote IDs as remote keys.
                    // This preserves user-facing references by remapping favorites/history through temporary ID maps.
    
                    // Channels
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS channels_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            stream_id INTEGER NOT NULL,
                            name TEXT NOT NULL,
                            logo_url TEXT,
                            group_title TEXT,
                            category_id INTEGER,
                            category_name TEXT,
                            stream_url TEXT NOT NULL,
                            epg_channel_id TEXT,
                            number INTEGER NOT NULL,
                            catch_up_supported INTEGER NOT NULL,
                            catch_up_days INTEGER NOT NULL,
    
                            catchUpSource TEXT,
                            provider_id INTEGER NOT NULL,
                            is_adult INTEGER NOT NULL,
                            is_user_protected INTEGER NOT NULL,
                            logical_group_id TEXT NOT NULL,
                            error_count INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        INSERT INTO channels_new (
                            stream_id, name, logo_url, group_title, category_id, category_name, stream_url,
                            epg_channel_id, number, catch_up_supported, catch_up_days, catchUpSource,
                            provider_id, is_adult, is_user_protected, logical_group_id, error_count
                        )
                        SELECT
                            stream_id, name, logo_url, group_title, category_id, category_name, stream_url,
                            epg_channel_id, number, catch_up_supported, catch_up_days, catchUpSource,
                            provider_id, is_adult, is_user_protected, logical_group_id, error_count
                        FROM channels
                        """.trimIndent()
                    )
                    database.execSQL("CREATE TEMP TABLE channel_id_map(old_id INTEGER PRIMARY KEY NOT NULL, new_id INTEGER NOT NULL)")
                    database.execSQL(
                        """
                        INSERT INTO channel_id_map(old_id, new_id)
                        SELECT old.id, MIN(new.id)
                        FROM channels old
                        JOIN channels_new new
                          ON new.provider_id = old.provider_id
                         AND new.stream_id = old.stream_id
                        GROUP BY old.id
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        UPDATE favorites
                        SET content_id = (SELECT new_id FROM channel_id_map WHERE old_id = content_id)
                        WHERE content_type = 'LIVE'
                          AND content_id IN (SELECT old_id FROM channel_id_map)
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        UPDATE playback_history
                        SET content_id = (SELECT new_id FROM channel_id_map WHERE old_id = content_id)
                        WHERE content_type = 'LIVE'
                          AND content_id IN (SELECT old_id FROM channel_id_map)
                        """.trimIndent()
                    )
                    database.execSQL("DROP TABLE channels")
                    database.execSQL("ALTER TABLE channels_new RENAME TO channels")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_channels_provider_id ON channels(provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_channels_provider_id_category_id ON channels(provider_id, category_id)")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_channels_provider_id_stream_id ON channels(provider_id, stream_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_channels_logical_group_id ON channels(logical_group_id)")
                    database.execSQL("DROP TABLE channel_id_map")
    
                    // Movies
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS movies_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            stream_id INTEGER NOT NULL,
                            name TEXT NOT NULL,
                            poster_url TEXT,
                            backdrop_url TEXT,
                            category_id INTEGER,
                            category_name TEXT,
                            stream_url TEXT NOT NULL,
                            container_extension TEXT,
                            plot TEXT,
                            "cast" TEXT,
                            director TEXT,
                            genre TEXT,
                            release_date TEXT,
                            duration TEXT,
                            duration_seconds INTEGER NOT NULL,
                            rating REAL NOT NULL,
                            year TEXT,
                            tmdb_id INTEGER,
                            youtube_trailer TEXT,
                            provider_id INTEGER NOT NULL,
                            watch_progress INTEGER NOT NULL,
                            last_watched_at INTEGER NOT NULL,
                            is_adult INTEGER NOT NULL,
                            is_user_protected INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        INSERT INTO movies_new (
                            stream_id, name, poster_url, backdrop_url, category_id, category_name, stream_url,
                            container_extension, plot, "cast", director, genre, release_date, duration, duration_seconds,
                            rating, year, tmdb_id, youtube_trailer, provider_id, watch_progress, last_watched_at,
                            is_adult, is_user_protected
                        )
                        SELECT
                            stream_id, name, poster_url, backdrop_url, category_id, category_name, stream_url,
                            container_extension, plot, "cast", director, genre, release_date, duration, duration_seconds,
                            rating, year, tmdb_id, youtube_trailer, provider_id, watch_progress, last_watched_at,
                            is_adult, is_user_protected
                        FROM movies
                        """.trimIndent()
                    )
                    database.execSQL("CREATE TEMP TABLE movie_id_map(old_id INTEGER PRIMARY KEY NOT NULL, new_id INTEGER NOT NULL)")
                    database.execSQL(
                        """
                        INSERT INTO movie_id_map(old_id, new_id)
                        SELECT old.id, MIN(new.id)
                        FROM movies old
                        JOIN movies_new new
                          ON new.provider_id = old.provider_id
                         AND new.stream_id = old.stream_id
                        GROUP BY old.id
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        UPDATE favorites
                        SET content_id = (SELECT new_id FROM movie_id_map WHERE old_id = content_id)
                        WHERE content_type = 'MOVIE'
                          AND content_id IN (SELECT old_id FROM movie_id_map)
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        UPDATE playback_history
                        SET content_id = (SELECT new_id FROM movie_id_map WHERE old_id = content_id)
                        WHERE content_type = 'MOVIE'
                          AND content_id IN (SELECT old_id FROM movie_id_map)
                        """.trimIndent()
                    )
                    database.execSQL("DROP TABLE movies")
                    database.execSQL("ALTER TABLE movies_new RENAME TO movies")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_movies_provider_id ON movies(provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_movies_provider_id_category_id ON movies(provider_id, category_id)")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_movies_provider_id_stream_id ON movies(provider_id, stream_id)")
                    database.execSQL("DROP TABLE movie_id_map")
    
                    // Series
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS series_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            series_id INTEGER NOT NULL,
                            name TEXT NOT NULL,
                            poster_url TEXT,
    
                            backdrop_url TEXT,
                            category_id INTEGER,
                            category_name TEXT,
                            plot TEXT,
                            "cast" TEXT,
                            director TEXT,
                            genre TEXT,
                            release_date TEXT,
                            rating REAL NOT NULL,
                            tmdb_id INTEGER,
                            youtube_trailer TEXT,
                            episode_run_time TEXT,
                            last_modified INTEGER NOT NULL,
                            provider_id INTEGER NOT NULL,
                            is_adult INTEGER NOT NULL,
                            is_user_protected INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        INSERT INTO series_new (
                            series_id, name, poster_url, backdrop_url, category_id, category_name, plot, "cast",
                            director, genre, release_date, rating, tmdb_id, youtube_trailer, episode_run_time,
                            last_modified, provider_id, is_adult, is_user_protected
                        )
                        SELECT
                            series_id, name, poster_url, backdrop_url, category_id, category_name, plot, "cast",
                            director, genre, release_date, rating, tmdb_id, youtube_trailer, episode_run_time,
                            last_modified, provider_id, is_adult, is_user_protected
                        FROM series
                        """.trimIndent()
                    )
                    database.execSQL("CREATE TEMP TABLE series_id_map(old_id INTEGER PRIMARY KEY NOT NULL, new_id INTEGER NOT NULL)")
                    database.execSQL(
                        """
                        INSERT INTO series_id_map(old_id, new_id)
                        SELECT old.id, MIN(new.id)
                        FROM series old
                        JOIN series_new new
                          ON new.provider_id = old.provider_id
                         AND new.series_id = old.series_id
                        GROUP BY old.id
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        UPDATE favorites
                        SET content_id = (SELECT new_id FROM series_id_map WHERE old_id = content_id)
                        WHERE content_type = 'SERIES'
                          AND content_id IN (SELECT old_id FROM series_id_map)
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        UPDATE playback_history
                        SET content_id = (SELECT new_id FROM series_id_map WHERE old_id = content_id)
                        WHERE content_type = 'SERIES'
                          AND content_id IN (SELECT old_id FROM series_id_map)
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        UPDATE playback_history
                        SET series_id = (SELECT new_id FROM series_id_map WHERE old_id = series_id)
                        WHERE series_id IS NOT NULL
                          AND series_id IN (SELECT old_id FROM series_id_map)
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        UPDATE episodes
                        SET series_id = (SELECT new_id FROM series_id_map WHERE old_id = series_id)
                        WHERE series_id IN (SELECT old_id FROM series_id_map)
                        """.trimIndent()
                    )
                    database.execSQL("DROP TABLE series")
                    database.execSQL("ALTER TABLE series_new RENAME TO series")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_series_provider_id ON series(provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_series_provider_id_category_id ON series(provider_id, category_id)")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_series_provider_id_series_id ON series(provider_id, series_id)")
                    database.execSQL("DROP TABLE series_id_map")
    
                    // Episodes
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS episodes_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            episode_id INTEGER NOT NULL,
                            title TEXT NOT NULL,
                            episode_number INTEGER NOT NULL,
                            season_number INTEGER NOT NULL,
                            stream_url TEXT NOT NULL,
                            container_extension TEXT,
                            cover_url TEXT,
                            plot TEXT,
                            duration TEXT,
                            duration_seconds INTEGER NOT NULL,
                            rating REAL NOT NULL,
                            release_date TEXT,
                            series_id INTEGER NOT NULL,
                            provider_id INTEGER NOT NULL,
                            watch_progress INTEGER NOT NULL,
                            last_watched_at INTEGER NOT NULL,
                            is_adult INTEGER NOT NULL,
                            is_user_protected INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        INSERT INTO episodes_new (
                            episode_id, title, episode_number, season_number, stream_url, container_extension, cover_url,
                            plot, duration, duration_seconds, rating, release_date, series_id, provider_id, watch_progress,
                            last_watched_at, is_adult, is_user_protected
                        )
                        SELECT
                            episode_id, title, episode_number, season_number, stream_url, container_extension, cover_url,
                            plot, duration, duration_seconds, rating, release_date, series_id, provider_id, watch_progress,
                            last_watched_at, is_adult, is_user_protected
                        FROM episodes
                        """.trimIndent()
                    )
                    database.execSQL("CREATE TEMP TABLE episode_id_map(old_id INTEGER PRIMARY KEY NOT NULL, new_id INTEGER NOT NULL)")
                    database.execSQL(
                        """
                        INSERT INTO episode_id_map(old_id, new_id)
                        SELECT old.id, new.id
                        FROM episodes old
                        JOIN episodes_new new
                          ON new.provider_id = old.provider_id
                         AND new.episode_id = old.episode_id
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        UPDATE playback_history
                        SET content_id = (SELECT new_id FROM episode_id_map WHERE old_id = content_id)
                        WHERE content_type = 'SERIES_EPISODE'
                          AND content_id IN (SELECT old_id FROM episode_id_map)
                        """.trimIndent()
                    )
                    database.execSQL("DROP TABLE episodes")
                    database.execSQL("ALTER TABLE episodes_new RENAME TO episodes")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_series_id ON episodes(series_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_provider_id ON episodes(provider_id)")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_episodes_provider_id_episode_id ON episodes(provider_id, episode_id)")
                    database.execSQL("DROP TABLE episode_id_map")
                    validateForeignKeys(database, "episodes")
                }
    
            }

    val MIGRATION_9_10 = object : Migration(9, 10) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS channels_fts USING fts4(name, content=`channels`)")
                    database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS movies_fts USING fts4(name, content=`movies`)")
                    database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS series_fts USING fts4(name, content=`series`)")
    
                    database.execSQL("INSERT INTO channels_fts(rowid, name) SELECT id, name FROM channels")
                    database.execSQL("INSERT INTO movies_fts(rowid, name) SELECT id, name FROM movies")
                    database.execSQL("INSERT INTO series_fts(rowid, name) SELECT id, name FROM series")
    
                    database.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS channels_ai
                        AFTER INSERT ON channels BEGIN
                            INSERT INTO channels_fts(rowid, name) VALUES (new.id, new.name);
                        END
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS channels_ad
                        BEFORE DELETE ON channels BEGIN
                            DELETE FROM channels_fts WHERE rowid = old.id;
                        END
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS channels_au
                        AFTER UPDATE OF name ON channels BEGIN
                            UPDATE channels_fts SET name = new.name WHERE rowid = old.id;
                        END
                        """.trimIndent()
                    )
    
                    database.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS movies_ai
                        AFTER INSERT ON movies BEGIN
                            INSERT INTO movies_fts(rowid, name) VALUES (new.id, new.name);
                        END
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS movies_ad
                        BEFORE DELETE ON movies BEGIN
                            DELETE FROM movies_fts WHERE rowid = old.id;
                        END
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS movies_au
                        AFTER UPDATE OF name ON movies BEGIN
                            UPDATE movies_fts SET name = new.name WHERE rowid = old.id;
                        END
                        """.trimIndent()
                    )
    
                    database.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS series_ai
                        AFTER INSERT ON series BEGIN
                            INSERT INTO series_fts(rowid, name) VALUES (new.id, new.name);
                        END
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS series_ad
                        BEFORE DELETE ON series BEGIN
                            DELETE FROM series_fts WHERE rowid = old.id;
                        END
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS series_au
                        AFTER UPDATE OF name ON series BEGIN
                            UPDATE series_fts SET name = new.name WHERE rowid = old.id;
                        END
                        """.trimIndent()
                    )
                }
            }
    
            /**
             * Migration 10 → 11: Add foreign key constraints to child tables.
             * Tables referencing providers get ON DELETE CASCADE.
             * Favorites referencing virtual_groups get ON DELETE SET NULL.
             * Programs table excluded (uses negative staging provider IDs).
             */

    val MIGRATION_10_11 = object : Migration(10, 11) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    // Clean up orphaned records before adding FK constraints
                    database.execSQL("DELETE FROM channels WHERE provider_id NOT IN (SELECT id FROM providers)")
                    database.execSQL("DELETE FROM movies WHERE provider_id NOT IN (SELECT id FROM providers)")
                    database.execSQL("DELETE FROM series WHERE provider_id NOT IN (SELECT id FROM providers)")
                    database.execSQL("DELETE FROM episodes WHERE provider_id NOT IN (SELECT id FROM providers)")
                    database.execSQL("DELETE FROM categories WHERE provider_id NOT IN (SELECT id FROM providers)")
                    database.execSQL("DELETE FROM playback_history WHERE provider_id NOT IN (SELECT id FROM providers)")
                    database.execSQL("DELETE FROM sync_metadata WHERE provider_id NOT IN (SELECT id FROM providers)")
                    database.execSQL("UPDATE favorites SET group_id = NULL WHERE group_id IS NOT NULL AND group_id NOT IN (SELECT id FROM virtual_groups)")
    
                    // ── Drop FTS triggers and tables (channels, movies, series) ──
                    database.execSQL("DROP TRIGGER IF EXISTS channels_ai")
                    database.execSQL("DROP TRIGGER IF EXISTS channels_ad")
                    database.execSQL("DROP TRIGGER IF EXISTS channels_au")
                    database.execSQL("DROP TRIGGER IF EXISTS movies_ai")
                    database.execSQL("DROP TRIGGER IF EXISTS movies_ad")
                    database.execSQL("DROP TRIGGER IF EXISTS movies_au")
                    database.execSQL("DROP TRIGGER IF EXISTS series_ai")
                    database.execSQL("DROP TRIGGER IF EXISTS series_ad")
                    database.execSQL("DROP TRIGGER IF EXISTS series_au")
                    database.execSQL("DROP TABLE IF EXISTS channels_fts")
                    database.execSQL("DROP TABLE IF EXISTS movies_fts")
                    database.execSQL("DROP TABLE IF EXISTS series_fts")
    
                    // ── Channels ──
                    database.execSQL("""
                        CREATE TABLE channels_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            stream_id INTEGER NOT NULL DEFAULT 0,
                            name TEXT NOT NULL,
                            logo_url TEXT,
                            group_title TEXT,
                            category_id INTEGER,
                            category_name TEXT,
                            stream_url TEXT NOT NULL DEFAULT '',
                            epg_channel_id TEXT,
                            number INTEGER NOT NULL DEFAULT 0,
                            catch_up_supported INTEGER NOT NULL DEFAULT 0,
                            catch_up_days INTEGER NOT NULL DEFAULT 0,
                            catchUpSource TEXT,
                            provider_id INTEGER NOT NULL DEFAULT 0,
                            is_adult INTEGER NOT NULL DEFAULT 0,
                            is_user_protected INTEGER NOT NULL DEFAULT 0,
                            logical_group_id TEXT NOT NULL DEFAULT '',
                            error_count INTEGER NOT NULL DEFAULT 0,
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    database.execSQL("INSERT INTO channels_new SELECT * FROM channels")
                    database.execSQL("DROP TABLE channels")
                    database.execSQL("ALTER TABLE channels_new RENAME TO channels")
                    database.execSQL("CREATE INDEX index_channels_provider_id ON channels(provider_id)")
                    database.execSQL("CREATE INDEX index_channels_provider_id_category_id ON channels(provider_id, category_id)")
    
                    database.execSQL("CREATE UNIQUE INDEX index_channels_provider_id_stream_id ON channels(provider_id, stream_id)")
                    database.execSQL("CREATE INDEX index_channels_logical_group_id ON channels(logical_group_id)")
    
                    // ── Movies ──
                    database.execSQL("""
                        CREATE TABLE movies_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            stream_id INTEGER NOT NULL DEFAULT 0,
                            name TEXT NOT NULL,
                            poster_url TEXT,
                            backdrop_url TEXT,
                            category_id INTEGER,
                            category_name TEXT,
                            stream_url TEXT NOT NULL DEFAULT '',
                            container_extension TEXT,
                            plot TEXT,
                            "cast" TEXT,
                            director TEXT,
                            genre TEXT,
                            release_date TEXT,
                            duration TEXT,
                            duration_seconds INTEGER NOT NULL DEFAULT 0,
                            rating REAL NOT NULL DEFAULT 0,
                            year TEXT,
                            tmdb_id INTEGER,
                            youtube_trailer TEXT,
                            provider_id INTEGER NOT NULL DEFAULT 0,
                            watch_progress INTEGER NOT NULL DEFAULT 0,
                            last_watched_at INTEGER NOT NULL DEFAULT 0,
                            is_adult INTEGER NOT NULL DEFAULT 0,
                            is_user_protected INTEGER NOT NULL DEFAULT 0,
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    database.execSQL("INSERT INTO movies_new SELECT * FROM movies")
                    database.execSQL("DROP TABLE movies")
                    database.execSQL("ALTER TABLE movies_new RENAME TO movies")
                    database.execSQL("CREATE INDEX index_movies_provider_id ON movies(provider_id)")
                    database.execSQL("CREATE INDEX index_movies_provider_id_category_id ON movies(provider_id, category_id)")
                    database.execSQL("CREATE UNIQUE INDEX index_movies_provider_id_stream_id ON movies(provider_id, stream_id)")
    
                    // ── Series ──
                    database.execSQL("""
                        CREATE TABLE series_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            series_id INTEGER NOT NULL DEFAULT 0,
                            name TEXT NOT NULL,
                            poster_url TEXT,
                            backdrop_url TEXT,
                            category_id INTEGER,
                            category_name TEXT,
                            plot TEXT,
                            "cast" TEXT,
                            director TEXT,
                            genre TEXT,
                            release_date TEXT,
                            rating REAL NOT NULL DEFAULT 0,
                            tmdb_id INTEGER,
                            youtube_trailer TEXT,
                            episode_run_time TEXT,
                            last_modified INTEGER NOT NULL DEFAULT 0,
                            provider_id INTEGER NOT NULL DEFAULT 0,
                            is_adult INTEGER NOT NULL DEFAULT 0,
                            is_user_protected INTEGER NOT NULL DEFAULT 0,
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    database.execSQL("INSERT INTO series_new SELECT * FROM series")
                    database.execSQL("DROP TABLE series")
                    database.execSQL("ALTER TABLE series_new RENAME TO series")
                    database.execSQL("CREATE INDEX index_series_provider_id ON series(provider_id)")
                    database.execSQL("CREATE INDEX index_series_provider_id_category_id ON series(provider_id, category_id)")
                    database.execSQL("CREATE UNIQUE INDEX index_series_provider_id_series_id ON series(provider_id, series_id)")
    
                    // ── Recreate FTS tables and triggers ──
                    database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS channels_fts USING fts4(name, content=`channels`)")
                    database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS movies_fts USING fts4(name, content=`movies`)")
                    database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS series_fts USING fts4(name, content=`series`)")
                    database.execSQL("INSERT INTO channels_fts(rowid, name) SELECT id, name FROM channels")
                    database.execSQL("INSERT INTO movies_fts(rowid, name) SELECT id, name FROM movies")
                    database.execSQL("INSERT INTO series_fts(rowid, name) SELECT id, name FROM series")
                    database.execSQL("CREATE TRIGGER IF NOT EXISTS channels_ai AFTER INSERT ON channels BEGIN INSERT INTO channels_fts(rowid, name) VALUES (new.id, new.name); END")
                    database.execSQL("CREATE TRIGGER IF NOT EXISTS channels_ad BEFORE DELETE ON channels BEGIN DELETE FROM channels_fts WHERE rowid = old.id; END")
                    database.execSQL("CREATE TRIGGER IF NOT EXISTS channels_au AFTER UPDATE OF name ON channels BEGIN UPDATE channels_fts SET name = new.name WHERE rowid = old.id; END")
                    database.execSQL("CREATE TRIGGER IF NOT EXISTS movies_ai AFTER INSERT ON movies BEGIN INSERT INTO movies_fts(rowid, name) VALUES (new.id, new.name); END")
                    database.execSQL("CREATE TRIGGER IF NOT EXISTS movies_ad BEFORE DELETE ON movies BEGIN DELETE FROM movies_fts WHERE rowid = old.id; END")
                    database.execSQL("CREATE TRIGGER IF NOT EXISTS movies_au AFTER UPDATE OF name ON movies BEGIN UPDATE movies_fts SET name = new.name WHERE rowid = old.id; END")
                    database.execSQL("CREATE TRIGGER IF NOT EXISTS series_ai AFTER INSERT ON series BEGIN INSERT INTO series_fts(rowid, name) VALUES (new.id, new.name); END")
                    database.execSQL("CREATE TRIGGER IF NOT EXISTS series_ad BEFORE DELETE ON series BEGIN DELETE FROM series_fts WHERE rowid = old.id; END")
                    database.execSQL("CREATE TRIGGER IF NOT EXISTS series_au AFTER UPDATE OF name ON series BEGIN UPDATE series_fts SET name = new.name WHERE rowid = old.id; END")
    
                    // ── Episodes ──
                    database.execSQL("""
                        CREATE TABLE episodes_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            episode_id INTEGER NOT NULL DEFAULT 0,
                            title TEXT NOT NULL,
                            episode_number INTEGER NOT NULL,
                            season_number INTEGER NOT NULL,
                            stream_url TEXT NOT NULL DEFAULT '',
                            container_extension TEXT,
                            cover_url TEXT,
                            plot TEXT,
                            duration TEXT,
                            duration_seconds INTEGER NOT NULL DEFAULT 0,
                            rating REAL NOT NULL DEFAULT 0,
                            release_date TEXT,
                            series_id INTEGER NOT NULL DEFAULT 0,
                            provider_id INTEGER NOT NULL DEFAULT 0,
                            watch_progress INTEGER NOT NULL DEFAULT 0,
                            last_watched_at INTEGER NOT NULL DEFAULT 0,
                            is_adult INTEGER NOT NULL DEFAULT 0,
                            is_user_protected INTEGER NOT NULL DEFAULT 0,
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    database.execSQL("INSERT INTO episodes_new SELECT * FROM episodes")
                    database.execSQL("DROP TABLE episodes")
                    database.execSQL("ALTER TABLE episodes_new RENAME TO episodes")
                    database.execSQL("CREATE INDEX index_episodes_series_id ON episodes(series_id)")
                    database.execSQL("CREATE INDEX index_episodes_provider_id ON episodes(provider_id)")
                    database.execSQL("CREATE UNIQUE INDEX index_episodes_provider_id_episode_id ON episodes(provider_id, episode_id)")
    
                    // ── Categories ──
                    database.execSQL("""
                        CREATE TABLE categories_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            category_id INTEGER NOT NULL DEFAULT 0,
                            name TEXT NOT NULL,
                            parent_id INTEGER,
                            type TEXT NOT NULL DEFAULT 'LIVE',
                            provider_id INTEGER NOT NULL DEFAULT 0,
                            is_adult INTEGER NOT NULL DEFAULT 0,
                            is_user_protected INTEGER NOT NULL DEFAULT 0,
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    database.execSQL("""
                        INSERT INTO categories_new (
                            id, category_id, name, parent_id, type, provider_id,
                            is_adult, is_user_protected
                        )
                        SELECT
                            id, category_id, name, parent_id, type, provider_id,
                            is_adult, is_user_protected
                        FROM categories
                    """.trimIndent())
                    database.execSQL("DROP TABLE categories")
                    database.execSQL("ALTER TABLE categories_new RENAME TO categories")
                    database.execSQL("CREATE INDEX index_categories_provider_id ON categories(provider_id)")
                    database.execSQL("CREATE UNIQUE INDEX index_categories_provider_id_category_id_type ON categories(provider_id, category_id, type)")
    
                    // ── Playback History ──
                    database.execSQL("""
                        CREATE TABLE playback_history_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            content_id INTEGER NOT NULL,
                            content_type TEXT NOT NULL,
                            provider_id INTEGER NOT NULL,
    
                            title TEXT NOT NULL DEFAULT '',
                            poster_url TEXT,
                            stream_url TEXT NOT NULL DEFAULT '',
                            resume_position_ms INTEGER NOT NULL DEFAULT 0,
                            total_duration_ms INTEGER NOT NULL DEFAULT 0,
                            last_watched_at INTEGER NOT NULL DEFAULT 0,
                            watch_count INTEGER NOT NULL DEFAULT 1,
                            series_id INTEGER,
                            season_number INTEGER,
                            episode_number INTEGER,
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    database.execSQL("INSERT INTO playback_history_new SELECT * FROM playback_history")
                    database.execSQL("DROP TABLE playback_history")
                    database.execSQL("ALTER TABLE playback_history_new RENAME TO playback_history")
                    database.execSQL("CREATE UNIQUE INDEX index_playback_history_content_id_content_type_provider_id ON playback_history(content_id, content_type, provider_id)")
                    database.execSQL("CREATE INDEX index_playback_history_last_watched_at ON playback_history(last_watched_at)")
                    database.execSQL("CREATE INDEX index_playback_history_provider_id ON playback_history(provider_id)")
    
                    // ── Sync Metadata ──
                    database.execSQL("""
                        CREATE TABLE sync_metadata_new (
                            provider_id INTEGER NOT NULL PRIMARY KEY,
                            last_live_sync INTEGER NOT NULL DEFAULT 0,
                            last_movie_sync INTEGER NOT NULL DEFAULT 0,
                            last_series_sync INTEGER NOT NULL DEFAULT 0,
                            last_epg_sync INTEGER NOT NULL DEFAULT 0,
                            live_count INTEGER NOT NULL DEFAULT 0,
                            movie_count INTEGER NOT NULL DEFAULT 0,
                            series_count INTEGER NOT NULL DEFAULT 0,
                            epg_count INTEGER NOT NULL DEFAULT 0,
                            last_sync_status TEXT NOT NULL DEFAULT 'NONE',
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    database.execSQL("INSERT INTO sync_metadata_new SELECT * FROM sync_metadata")
                    database.execSQL("DROP TABLE sync_metadata")
                    database.execSQL("ALTER TABLE sync_metadata_new RENAME TO sync_metadata")
    
                    // ── Favorites ──
                    database.execSQL("""
                        CREATE TABLE favorites_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            content_id INTEGER NOT NULL,
                            content_type TEXT NOT NULL,
                            position INTEGER NOT NULL DEFAULT 0,
                            group_id INTEGER,
                            added_at INTEGER NOT NULL,
                            FOREIGN KEY(group_id) REFERENCES virtual_groups(id) ON DELETE SET NULL
                        )
                    """.trimIndent())
                    database.execSQL("INSERT INTO favorites_new SELECT * FROM favorites")
                    database.execSQL("DROP TABLE favorites")
                    database.execSQL("ALTER TABLE favorites_new RENAME TO favorites")
                    database.execSQL("CREATE UNIQUE INDEX index_favorites_content_id_content_type_group_id ON favorites(content_id, content_type, group_id)")
                    database.execSQL("CREATE INDEX index_favorites_content_type_group_id ON favorites(content_type, group_id)")
                    database.execSQL("CREATE INDEX index_favorites_group_id_position ON favorites(group_id, position)")
                    validateForeignKeys(database, "favorites")
                }
            }
            /**
             * Migration 11 → 12: adds series_id FK to episodes.
             * Episodes whose series_id has no matching row in the series table are silently
             * removed — they are unreachable orphans and would violate the new constraint.
             */

    val MIGRATION_11_12 = object : Migration(11, 12) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    // Create new episodes table with both FK constraints
                    database.execSQL("""
                        CREATE TABLE episodes_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            episode_id INTEGER NOT NULL DEFAULT 0,
                            title TEXT NOT NULL,
                            episode_number INTEGER NOT NULL,
                            season_number INTEGER NOT NULL,
                            stream_url TEXT NOT NULL DEFAULT '',
                            container_extension TEXT,
                            cover_url TEXT,
                            plot TEXT,
                            duration TEXT,
                            duration_seconds INTEGER NOT NULL DEFAULT 0,
                            rating REAL NOT NULL DEFAULT 0,
                            release_date TEXT,
                            series_id INTEGER NOT NULL DEFAULT 0,
                            provider_id INTEGER NOT NULL DEFAULT 0,
                            watch_progress INTEGER NOT NULL DEFAULT 0,
                            last_watched_at INTEGER NOT NULL DEFAULT 0,
                            is_adult INTEGER NOT NULL DEFAULT 0,
                            is_user_protected INTEGER NOT NULL DEFAULT 0,
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE,
                            FOREIGN KEY(series_id) REFERENCES series(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    // Migrate only episodes with a valid series parent; orphans are discarded
                    database.execSQL("""
                        INSERT INTO episodes_new
                        SELECT * FROM episodes
                        WHERE series_id IN (SELECT id FROM series)
                    """.trimIndent())
                    database.execSQL("DROP TABLE episodes")
                    database.execSQL("ALTER TABLE episodes_new RENAME TO episodes")
                    database.execSQL("CREATE INDEX index_episodes_series_id ON episodes(series_id)")
                    database.execSQL("CREATE INDEX index_episodes_provider_id ON episodes(provider_id)")
                    database.execSQL("CREATE UNIQUE INDEX index_episodes_provider_id_episode_id ON episodes(provider_id, episode_id)")
                }
            }
    
            /**
             * Migration 12 → 13: move high-cardinality per-channel UI state into Room.
             * Existing DataStore aspect-ratio entries are read lazily as a legacy fallback and
             * get replaced in Room on the next write for each channel.
             */

    val MIGRATION_12_13 = object : Migration(12, 13) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS channel_preferences (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            channel_id INTEGER NOT NULL,
                            aspect_ratio TEXT,
                            updated_at INTEGER NOT NULL DEFAULT 0,
                            FOREIGN KEY(channel_id) REFERENCES channels(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_channel_preferences_channel_id ON channel_preferences(channel_id)"
                    )
                }
            }
    
            /**
             * Migration 13 → 14: add targeted secondary indexes for broad EPG windows,
             * provider/type category filtering, and virtual group content type lookups.
             */

    val MIGRATION_13_14 = object : Migration(13, 14) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_programs_provider_id_start_time_end_time ON programs(provider_id, start_time, end_time)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_categories_provider_id_type ON categories(provider_id, type)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_virtual_groups_content_type ON virtual_groups(content_type)"
                    )
                }
            }

    val MIGRATION_14_15 = object : Migration(14, 15) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE providers ADD COLUMN api_version TEXT")
                    database.execSQL("ALTER TABLE channels ADD COLUMN quality_options_json TEXT")
                    database.execSQL("ALTER TABLE programs ADD COLUMN rating TEXT")
                    database.execSQL("ALTER TABLE programs ADD COLUMN image_url TEXT")
                    database.execSQL("ALTER TABLE programs ADD COLUMN genre TEXT")
                    database.execSQL("ALTER TABLE programs ADD COLUMN category TEXT")
                    database.execSQL("ALTER TABLE playback_history ADD COLUMN watched_status TEXT NOT NULL DEFAULT 'IN_PROGRESS'")
                }
            }

    val MIGRATION_15_16 = object : Migration(15, 16) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_channels_provider_id_category_id_logical_group_id " +
                            "ON channels(provider_id, category_id, logical_group_id)"
                    )
                }
            }

    val MIGRATION_16_17 = object : Migration(16, 17) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE providers ADD COLUMN allowed_output_formats_json TEXT NOT NULL DEFAULT '[]'"
                    )
                }
            }

    val MIGRATION_17_18 = object : Migration(17, 18) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE sync_metadata ADD COLUMN last_movie_attempt INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE sync_metadata ADD COLUMN last_movie_success INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE sync_metadata ADD COLUMN last_movie_partial INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE sync_metadata ADD COLUMN movie_sync_mode TEXT NOT NULL DEFAULT 'UNKNOWN'")
                    database.execSQL("ALTER TABLE sync_metadata ADD COLUMN movie_warnings_count INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE sync_metadata ADD COLUMN movie_catalog_stale INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE sync_metadata ADD COLUMN movie_parallel_failures_remembered INTEGER NOT NULL DEFAULT 0")
                }
            }

    val MIGRATION_18_19 = object : Migration(18, 19) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE sync_metadata ADD COLUMN movie_healthy_sync_streak INTEGER NOT NULL DEFAULT 0")
                }
            }

    val MIGRATION_19_20 = object : Migration(19, 20) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE channels ADD COLUMN sync_fingerprint TEXT NOT NULL DEFAULT ''")
                    database.execSQL("ALTER TABLE movies ADD COLUMN sync_fingerprint TEXT NOT NULL DEFAULT ''")
                    database.execSQL("ALTER TABLE series ADD COLUMN sync_fingerprint TEXT NOT NULL DEFAULT ''")
                    database.execSQL("ALTER TABLE categories ADD COLUMN sync_fingerprint TEXT NOT NULL DEFAULT ''")
    
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS channel_import_stage (
                            session_id INTEGER NOT NULL,
                            provider_id INTEGER NOT NULL,
                            stream_id INTEGER NOT NULL,
                            name TEXT NOT NULL,
                            logo_url TEXT,
                            group_title TEXT,
                            category_id INTEGER,
                            category_name TEXT,
                            stream_url TEXT NOT NULL,
                            epg_channel_id TEXT,
                            number INTEGER NOT NULL,
                            catch_up_supported INTEGER NOT NULL,
                            catch_up_days INTEGER NOT NULL,
                            catchUpSource TEXT,
                            is_adult INTEGER NOT NULL,
                            sync_fingerprint TEXT NOT NULL,
                            PRIMARY KEY(session_id, provider_id, stream_id),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_channel_import_stage_provider_id ON channel_import_stage(provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_channel_import_stage_session_id_provider_id ON channel_import_stage(session_id, provider_id)")
    
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS movie_import_stage (
                            session_id INTEGER NOT NULL,
                            provider_id INTEGER NOT NULL,
                            stream_id INTEGER NOT NULL,
                            name TEXT NOT NULL,
                            poster_url TEXT,
                            backdrop_url TEXT,
                            category_id INTEGER,
                            category_name TEXT,
                            stream_url TEXT NOT NULL,
                            container_extension TEXT,
                            plot TEXT,
                            "cast" TEXT,
                            director TEXT,
                            genre TEXT,
                            release_date TEXT,
                            duration TEXT,
                            duration_seconds INTEGER NOT NULL,
                            rating REAL NOT NULL,
                            year TEXT,
                            tmdb_id INTEGER,
                            youtube_trailer TEXT,
                            is_adult INTEGER NOT NULL,
                            sync_fingerprint TEXT NOT NULL,
                            PRIMARY KEY(session_id, provider_id, stream_id),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_movie_import_stage_provider_id ON movie_import_stage(provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_movie_import_stage_session_id_provider_id ON movie_import_stage(session_id, provider_id)")
    
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS series_import_stage (
                            session_id INTEGER NOT NULL,
                            provider_id INTEGER NOT NULL,
                            series_id INTEGER NOT NULL,
                            name TEXT NOT NULL,
                            poster_url TEXT,
                            backdrop_url TEXT,
                            category_id INTEGER,
                            category_name TEXT,
                            plot TEXT,
                            "cast" TEXT,
                            director TEXT,
                            genre TEXT,
                            release_date TEXT,
                            rating REAL NOT NULL,
                            tmdb_id INTEGER,
                            youtube_trailer TEXT,
                            episode_run_time TEXT,
                            last_modified INTEGER NOT NULL,
                            is_adult INTEGER NOT NULL,
                            sync_fingerprint TEXT NOT NULL,
                            PRIMARY KEY(session_id, provider_id, series_id),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_series_import_stage_provider_id ON series_import_stage(provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_series_import_stage_session_id_provider_id ON series_import_stage(session_id, provider_id)")
    
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS category_import_stage (
                            session_id INTEGER NOT NULL,
    
                            provider_id INTEGER NOT NULL,
                            category_id INTEGER NOT NULL,
                            name TEXT NOT NULL,
                            parent_id INTEGER,
                            type TEXT NOT NULL,
                            is_adult INTEGER NOT NULL,
                            sync_fingerprint TEXT NOT NULL,
                            PRIMARY KEY(session_id, provider_id, category_id, type),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_category_import_stage_provider_id ON category_import_stage(provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_category_import_stage_session_id_provider_id ON category_import_stage(session_id, provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_category_import_stage_provider_id_type ON category_import_stage(provider_id, type)")
                }
            }

    val MIGRATION_20_21 = object : Migration(20, 21) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE sync_metadata ADD COLUMN live_avoid_full_until INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE sync_metadata ADD COLUMN movie_avoid_full_until INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE sync_metadata ADD COLUMN series_avoid_full_until INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE sync_metadata ADD COLUMN live_sequential_failures_remembered INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE sync_metadata ADD COLUMN live_healthy_sync_streak INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE sync_metadata ADD COLUMN series_sequential_failures_remembered INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE sync_metadata ADD COLUMN series_healthy_sync_streak INTEGER NOT NULL DEFAULT 0")
                }
            }

    val MIGRATION_21_22 = object : Migration(21, 22) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE channel_import_stage ADD COLUMN logical_group_id TEXT NOT NULL DEFAULT ''")
                }
            }

    val MIGRATION_22_23 = object : Migration(22, 23) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE channel_import_stage ADD COLUMN error_count INTEGER NOT NULL DEFAULT 0")
                }
            }

    val MIGRATION_23_24 = object : Migration(23, 24) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE providers ADD COLUMN xtream_fast_sync_enabled INTEGER NOT NULL DEFAULT 0")
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS movie_category_hydration (
                            provider_id INTEGER NOT NULL,
                            category_id INTEGER NOT NULL,
                            last_hydrated_at INTEGER NOT NULL DEFAULT 0,
                            item_count INTEGER NOT NULL DEFAULT 0,
                            last_status TEXT NOT NULL DEFAULT 'IDLE',
                            last_error TEXT,
                            PRIMARY KEY(provider_id, category_id),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_movie_category_hydration_provider_id ON movie_category_hydration(provider_id)"
                    )
                }
            }

    val all: List<Migration> = listOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18,
        MIGRATION_18_19,
        MIGRATION_19_20,
        MIGRATION_20_21,
        MIGRATION_21_22,
        MIGRATION_22_23,
        MIGRATION_23_24
    )
}
