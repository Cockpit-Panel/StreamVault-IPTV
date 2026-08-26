@file:Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE", "TYPE_INTERSECTION_AS_REIFIED")

package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/** Catalog, guide, recording, and playback-history migrations (v24 through v49). Executable bodies live here, outside the Room schema declaration. */
internal object LegacyMigrationsV24To49 {
    val MIGRATION_24_25 = object : Migration(24, 25) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    // ── epg_sources ──
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS epg_sources (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            url TEXT NOT NULL,
                            enabled INTEGER NOT NULL DEFAULT 1,
                            last_refresh_at INTEGER NOT NULL DEFAULT 0,
                            last_success_at INTEGER NOT NULL DEFAULT 0,
                            last_error TEXT,
                            priority INTEGER NOT NULL DEFAULT 0,
                            created_at INTEGER NOT NULL DEFAULT 0,
                            updated_at INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_epg_sources_url ON epg_sources(url)")
    
                    // ── provider_epg_sources ──
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS provider_epg_sources (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            provider_id INTEGER NOT NULL,
                            epg_source_id INTEGER NOT NULL,
                            priority INTEGER NOT NULL DEFAULT 0,
                            enabled INTEGER NOT NULL DEFAULT 1,
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE,
                            FOREIGN KEY(epg_source_id) REFERENCES epg_sources(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_provider_epg_sources_provider_id_epg_source_id ON provider_epg_sources(provider_id, epg_source_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_provider_epg_sources_epg_source_id ON provider_epg_sources(epg_source_id)")
    
                    // ── epg_channels ──
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS epg_channels (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            epg_source_id INTEGER NOT NULL,
                            xmltv_channel_id TEXT NOT NULL,
                            display_name TEXT NOT NULL,
                            normalized_name TEXT NOT NULL DEFAULT '',
                            icon_url TEXT,
                            FOREIGN KEY(epg_source_id) REFERENCES epg_sources(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_epg_channels_epg_source_id_xmltv_channel_id ON epg_channels(epg_source_id, xmltv_channel_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_epg_channels_epg_source_id ON epg_channels(epg_source_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_epg_channels_normalized_name ON epg_channels(normalized_name)")
    
                    // ── epg_programmes ──
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS epg_programmes (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            epg_source_id INTEGER NOT NULL,
                            xmltv_channel_id TEXT NOT NULL,
                            start_time INTEGER NOT NULL DEFAULT 0,
                            end_time INTEGER NOT NULL DEFAULT 0,
                            title TEXT NOT NULL,
                            subtitle TEXT,
                            description TEXT NOT NULL DEFAULT '',
    
                            category TEXT,
                            lang TEXT NOT NULL DEFAULT '',
                            rating TEXT,
                            image_url TEXT,
                            episode_info TEXT,
                            FOREIGN KEY(epg_source_id) REFERENCES epg_sources(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_epg_programmes_epg_source_id_xmltv_channel_id_start_time ON epg_programmes(epg_source_id, xmltv_channel_id, start_time)")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_epg_programmes_epg_source_id_xmltv_channel_id_start_time_end_time ON epg_programmes(epg_source_id, xmltv_channel_id, start_time, end_time)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_epg_programmes_epg_source_id ON epg_programmes(epg_source_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_epg_programmes_start_time ON epg_programmes(start_time)")
    
                    // ── channel_epg_mappings ──
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS channel_epg_mappings (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            provider_channel_id INTEGER NOT NULL,
                            provider_id INTEGER NOT NULL,
                            source_type TEXT NOT NULL DEFAULT 'NONE',
                            epg_source_id INTEGER,
                            xmltv_channel_id TEXT,
                            match_type TEXT,
                            confidence REAL NOT NULL DEFAULT 0,
                            is_manual_override INTEGER NOT NULL DEFAULT 0,
                            updated_at INTEGER NOT NULL DEFAULT 0,
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_channel_epg_mappings_provider_id_provider_channel_id ON channel_epg_mappings(provider_id, provider_channel_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_channel_epg_mappings_provider_id ON channel_epg_mappings(provider_id)")
                }
            }

    val MIGRATION_25_26 = object : Migration(25, 26) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS series_category_hydration (
                            provider_id INTEGER NOT NULL,
                            category_id INTEGER NOT NULL,
                            last_hydrated_at INTEGER NOT NULL DEFAULT 0,
                            item_count INTEGER NOT NULL DEFAULT 0,
                            last_status TEXT NOT NULL DEFAULT 'IDLE',
                            last_error TEXT,
                            PRIMARY KEY(provider_id, category_id),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_series_category_hydration_provider_id ON series_category_hydration(provider_id)")
                }
            }

    val MIGRATION_26_27 = object : Migration(26, 27) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE providers ADD COLUMN epg_sync_mode TEXT NOT NULL DEFAULT 'UPFRONT'")
                }
            }

    val MIGRATION_27_28 = object : Migration(27, 28) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE providers ADD COLUMN m3u_vod_classification_enabled INTEGER NOT NULL DEFAULT 1")
                }
            }

    val MIGRATION_28_29 = object : Migration(28, 29) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS recording_schedules (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            provider_id INTEGER NOT NULL,
                            channel_id INTEGER NOT NULL,
                            channel_name TEXT NOT NULL,
                            stream_url TEXT NOT NULL,
                            program_title TEXT,
                            requested_start_ms INTEGER NOT NULL,
                            requested_end_ms INTEGER NOT NULL,
                            recurrence TEXT NOT NULL,
                            recurring_rule_id TEXT,
                            enabled INTEGER NOT NULL DEFAULT 1,
                            is_manual INTEGER NOT NULL DEFAULT 0,
                            priority INTEGER NOT NULL DEFAULT 0,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_recording_schedules_provider_id ON recording_schedules(provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_recording_schedules_enabled_requested_start_ms ON recording_schedules(enabled, requested_start_ms)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_recording_schedules_recurring_rule_id ON recording_schedules(recurring_rule_id)")
    
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS recording_runs (
                            id TEXT PRIMARY KEY NOT NULL,
                            schedule_id INTEGER NOT NULL,
                            provider_id INTEGER NOT NULL,
                            channel_id INTEGER NOT NULL,
                            channel_name TEXT NOT NULL,
                            stream_url TEXT NOT NULL,
                            program_title TEXT,
                            scheduled_start_ms INTEGER NOT NULL,
                            scheduled_end_ms INTEGER NOT NULL,
                            recurrence TEXT NOT NULL,
                            recurring_rule_id TEXT,
                            status TEXT NOT NULL,
                            source_type TEXT NOT NULL,
                            resolved_url TEXT,
                            headers_json TEXT NOT NULL DEFAULT '{}',
                            user_agent TEXT,
                            expiration_time INTEGER,
                            provider_label TEXT,
                            output_uri TEXT,
                            output_display_path TEXT,
                            bytes_written INTEGER NOT NULL DEFAULT 0,
                            average_throughput_bps INTEGER NOT NULL DEFAULT 0,
                            retry_count INTEGER NOT NULL DEFAULT 0,
                            last_progress_at_ms INTEGER,
    
                            failure_category TEXT NOT NULL DEFAULT 'NONE',
                            failure_reason TEXT,
                            terminal_at_ms INTEGER,
                            started_at_ms INTEGER,
                            ended_at_ms INTEGER,
                            schedule_enabled INTEGER NOT NULL DEFAULT 1,
                            priority INTEGER NOT NULL DEFAULT 0,
                            alarm_start_at_ms INTEGER,
                            alarm_stop_at_ms INTEGER,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            FOREIGN KEY(schedule_id) REFERENCES recording_schedules(id) ON DELETE CASCADE,
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_recording_runs_schedule_id ON recording_runs(schedule_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_recording_runs_provider_id ON recording_runs(provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_recording_runs_status_scheduled_start_ms ON recording_runs(status, scheduled_start_ms)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_recording_runs_alarm_start_at_ms ON recording_runs(alarm_start_at_ms)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_recording_runs_alarm_stop_at_ms ON recording_runs(alarm_stop_at_ms)")
    
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS recording_storage (
                            id INTEGER PRIMARY KEY NOT NULL,
                            tree_uri TEXT,
                            display_name TEXT,
                            output_directory TEXT,
                            available_bytes INTEGER,
                            is_writable INTEGER NOT NULL DEFAULT 0,
                            file_name_pattern TEXT NOT NULL DEFAULT 'ChannelName_yyyy-MM-dd_HH-mm_ProgramTitle.ts',
                            retention_days INTEGER,
                            max_simultaneous_recordings INTEGER NOT NULL DEFAULT 2,
                            updated_at INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    val now = System.currentTimeMillis()
                    database.execSQL(
                        """
                        INSERT OR IGNORE INTO recording_storage (
                            id, tree_uri, display_name, output_directory, available_bytes, is_writable, file_name_pattern,
                            retention_days, max_simultaneous_recordings, updated_at
                        ) VALUES (
                            1, NULL, NULL, NULL, NULL, 0, 'ChannelName_yyyy-MM-dd_HH-mm_ProgramTitle.ts', NULL, 2, $now
                        )
                        """.trimIndent()
                    )
                    validateForeignKeys(database, "recording_schedules", "recording_runs")
                }
            }

    val MIGRATION_29_30 = object : Migration(29, 30) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS combined_m3u_profiles (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            enabled INTEGER NOT NULL DEFAULT 1,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS combined_m3u_profile_members (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            profile_id INTEGER NOT NULL,
                            provider_id INTEGER NOT NULL,
                            priority INTEGER NOT NULL,
                            enabled INTEGER NOT NULL DEFAULT 1,
                            FOREIGN KEY(profile_id) REFERENCES combined_m3u_profiles(id) ON DELETE CASCADE,
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_combined_m3u_profile_members_profile_id ON combined_m3u_profile_members(profile_id)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_combined_m3u_profile_members_provider_id ON combined_m3u_profile_members(provider_id)"
                    )
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_combined_m3u_profile_members_profile_id_provider_id ON combined_m3u_profile_members(profile_id, provider_id)"
                    )
                }
            }

    val MIGRATION_30_31 = object : Migration(30, 31) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE movies ADD COLUMN added_at INTEGER NOT NULL DEFAULT 0")
                }
            }

    val MIGRATION_31_32 = object : Migration(31, 32) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE movie_import_stage ADD COLUMN added_at INTEGER NOT NULL DEFAULT 0")
                    fun firstLong(query: String): Long? = database.query(query).use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                    }
    
                    // FK enforcement was never enabled at runtime (setForeignKeyConstraintsEnabled was
                    // never called), so ON DELETE CASCADE never fired when a provider was deleted.
                    // Purge orphaned content rows now so the provider_id inference below can only
                    // ever produce valid FK values. Favorites pointing to purged content will resolve
                    // to NULL and be silently dropped via the `continue` below.
                    database.execSQL("DELETE FROM channels WHERE provider_id NOT IN (SELECT id FROM providers)")
                    database.execSQL("DELETE FROM movies WHERE provider_id NOT IN (SELECT id FROM providers)")
                    database.execSQL("DELETE FROM series WHERE provider_id NOT IN (SELECT id FROM providers)")
    
                    val defaultProviderId = firstLong(
                        "SELECT id FROM providers WHERE is_active = 1 ORDER BY id LIMIT 1"
                    ) ?: firstLong(
                        "SELECT id FROM providers ORDER BY id LIMIT 1"
                    )
    
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS virtual_groups_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            provider_id INTEGER NOT NULL,
                            name TEXT NOT NULL,
                            icon_emoji TEXT,
                            position INTEGER NOT NULL,
                            created_at INTEGER NOT NULL,
                            content_type TEXT NOT NULL,
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
    
                    val providersByLegacyGroup = mutableMapOf<Long, MutableSet<Long>>()
                    database.query(
                        """
                        SELECT f.group_id,
                               CASE f.content_type
                                   WHEN 'LIVE' THEN (SELECT provider_id FROM channels WHERE id = f.content_id)
                                   WHEN 'MOVIE' THEN (SELECT provider_id FROM movies WHERE id = f.content_id)
                                   WHEN 'SERIES' THEN (SELECT provider_id FROM series WHERE id = f.content_id)
                                   ELSE NULL
                               END AS provider_id
                        FROM favorites f
                        WHERE f.group_id IS NOT NULL
                        """.trimIndent()
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            if (cursor.isNull(0) || cursor.isNull(1)) continue
                            val groupId = cursor.getLong(0)
    
                            val providerId = cursor.getLong(1)
                            providersByLegacyGroup.getOrPut(groupId) { linkedSetOf() }.add(providerId)
                        }
                    }
    
                    val groupProviderToNewId = mutableMapOf<Pair<Long, Long>, Long>()
                    var nextGroupId = (firstLong("SELECT MAX(id) FROM virtual_groups") ?: 0L) + 1L
    
                    database.query(
                        "SELECT id, name, icon_emoji, position, created_at, content_type FROM virtual_groups ORDER BY id"
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val legacyGroupId = cursor.getLong(0)
                            val name = cursor.getString(1)
                            val iconEmoji = if (cursor.isNull(2)) null else cursor.getString(2)
                            val position = cursor.getInt(3)
                            val createdAt = cursor.getLong(4)
                            val contentType = cursor.getString(5)
                            val providerIds = providersByLegacyGroup[legacyGroupId]
                                ?.toList()
                                ?.sorted()
                                ?: defaultProviderId?.let(::listOf)
                                ?: emptyList()
    
                            providerIds.forEachIndexed { index, providerId ->
                                val newGroupId = if (index == 0) legacyGroupId else nextGroupId++
                                groupProviderToNewId[legacyGroupId to providerId] = newGroupId
                                database.execSQL(
                                    """
                                    INSERT INTO virtual_groups_new(
                                        id, provider_id, name, icon_emoji, position, created_at, content_type
                                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                                    """.trimIndent(),
                                    arrayOf<Any?>(newGroupId, providerId, name, iconEmoji, position, createdAt, contentType)
                                )
                            }
                        }
                    }
    
                    database.execSQL("ALTER TABLE virtual_groups RENAME TO virtual_groups_legacy")
                    database.execSQL("ALTER TABLE virtual_groups_new RENAME TO virtual_groups")
                    database.execSQL("DROP INDEX IF EXISTS index_virtual_groups_position")
                    database.execSQL("DROP INDEX IF EXISTS index_virtual_groups_content_type")
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_virtual_groups_provider_id_content_type ON virtual_groups(provider_id, content_type)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_virtual_groups_position ON virtual_groups(position)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_virtual_groups_content_type ON virtual_groups(content_type)"
                    )
    
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS favorites_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            provider_id INTEGER NOT NULL,
                            content_id INTEGER NOT NULL,
                            content_type TEXT NOT NULL,
                            position INTEGER NOT NULL,
                            group_id INTEGER,
                            added_at INTEGER NOT NULL,
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE,
                            FOREIGN KEY(group_id) REFERENCES virtual_groups(id) ON DELETE SET NULL
                        )
                        """.trimIndent()
                    )
    
                    database.query(
                        """
                        SELECT f.id, f.content_id, f.content_type, f.position, f.group_id, f.added_at,
                               CASE f.content_type
                                   WHEN 'LIVE' THEN (SELECT provider_id FROM channels WHERE id = f.content_id)
                                   WHEN 'MOVIE' THEN (SELECT provider_id FROM movies WHERE id = f.content_id)
                                   WHEN 'SERIES' THEN (SELECT provider_id FROM series WHERE id = f.content_id)
                                   ELSE NULL
                               END AS provider_id
                        FROM favorites f
                        ORDER BY f.id ASC
                        """.trimIndent()
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val favoriteId = cursor.getLong(0)
                            val contentId = cursor.getLong(1)
                            val contentType = cursor.getString(2)
                            val position = cursor.getInt(3)
                            val legacyGroupId = if (cursor.isNull(4)) null else cursor.getLong(4)
                            val addedAt = cursor.getLong(5)
                            val providerId = when {
                                !cursor.isNull(6) -> cursor.getLong(6)
                                legacyGroupId != null -> groupProviderToNewId.keys
                                    .firstOrNull { it.first == legacyGroupId }
                                    ?.second
                                else -> null
                            } ?: continue
    
                            val newGroupId = legacyGroupId?.let { groupId ->
                                groupProviderToNewId[groupId to providerId]
                                    ?: groupProviderToNewId.entries.firstOrNull { it.key.first == groupId }?.value
                            }
    
                            database.execSQL(
                                """
                                INSERT OR REPLACE INTO favorites_new(
                                    id, provider_id, content_id, content_type, position, group_id, added_at
                                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                                """.trimIndent(),
                                arrayOf<Any?>(favoriteId, providerId, contentId, contentType, position, newGroupId, addedAt)
                            )
                        }
                    }
    
                    database.execSQL("ALTER TABLE favorites RENAME TO favorites_legacy")
                    database.execSQL("ALTER TABLE favorites_new RENAME TO favorites")
                    database.execSQL("DROP INDEX IF EXISTS index_favorites_group_id_position")
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_favorites_provider_id_content_id_content_type_group_id ON favorites(provider_id, content_id, content_type, group_id)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_favorites_provider_id_content_type_group_id ON favorites(provider_id, content_type, group_id)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_favorites_group_id_position ON favorites(group_id, position)"
                    )
    
                    database.execSQL("DROP TABLE favorites_legacy")
                    database.execSQL("DROP TABLE virtual_groups_legacy")
                    validateForeignKeys(database, "virtual_groups", "favorites")
                }
            }

    val MIGRATION_32_33 = object : Migration(32, 33) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE movies ADD COLUMN watch_count INTEGER NOT NULL DEFAULT 0")
                    database.execSQL(
                        """
                        UPDATE movies
                        SET watch_count = COALESCE((
                            SELECT playback_history.watch_count
                            FROM playback_history
                            WHERE playback_history.content_id = movies.id
                              AND playback_history.content_type = 'MOVIE'
                              AND playback_history.provider_id = movies.provider_id
                        ), 0)
                        """.trimIndent()
                    )
                }
            }

    val MIGRATION_33_34 = object : Migration(33, 34) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE sync_metadata ADD COLUMN last_live_success INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE sync_metadata ADD COLUMN last_series_success INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE sync_metadata ADD COLUMN last_epg_success INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("UPDATE sync_metadata SET last_live_success = last_live_sync")
                    database.execSQL("UPDATE sync_metadata SET last_series_success = last_series_sync")
                    database.execSQL("UPDATE sync_metadata SET last_epg_success = last_epg_sync")
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_programs_provider_id_end_time_channel_id ON programs(provider_id, end_time, channel_id)"
                    )
                    // No FK-bearing rows added; only column additions and indexes.
                }
            }

    val MIGRATION_34_35 = object : Migration(34, 35) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS search_history (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            query TEXT NOT NULL,
                            content_scope TEXT NOT NULL,
                            provider_id INTEGER NOT NULL DEFAULT 0,
                            used_at INTEGER NOT NULL,
                            use_count INTEGER NOT NULL DEFAULT 1
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_search_history_content_scope_provider_id_used_at ON search_history(content_scope, provider_id, used_at)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_search_history_used_at ON search_history(used_at)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_search_history_provider_id ON search_history(provider_id)"
                    )
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_search_history_query_content_scope_provider_id ON search_history(query, content_scope, provider_id)"
                    )
                    // search_history has no FK columns; no FK check needed.
                }
            }

    val MIGRATION_35_36 = object : Migration(35, 36) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE channel_epg_mappings ADD COLUMN matched_at INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE channel_epg_mappings ADD COLUMN failed_attempts INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE channel_epg_mappings ADD COLUMN source TEXT")
    
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS program_reminders (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            provider_id INTEGER NOT NULL,
                            channel_id TEXT NOT NULL,
                            channel_name TEXT NOT NULL,
                            program_title TEXT NOT NULL,
                            program_start_time INTEGER NOT NULL,
                            remind_at INTEGER NOT NULL,
                            lead_time_minutes INTEGER NOT NULL DEFAULT 5,
                            is_dismissed INTEGER NOT NULL DEFAULT 0,
                            notified_at INTEGER,
                            created_at INTEGER NOT NULL,
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_program_reminders_provider_id_remind_at ON program_reminders(provider_id, remind_at)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_program_reminders_is_dismissed_notified_at_remind_at ON program_reminders(is_dismissed, notified_at, remind_at)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_program_reminders_provider_id_channel_id_program_start_time ON program_reminders(provider_id, channel_id, program_start_time)"
                    )
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_program_reminders_provider_id_channel_id_program_title_program_start_time ON program_reminders(provider_id, channel_id, program_title, program_start_time)"
                    )
                    validateForeignKeys(database, "program_reminders")
                }
            }

    val MIGRATION_36_37 = object : Migration(36, 37) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS tmdb_identity (
                            tmdb_id INTEGER NOT NULL,
                            content_type TEXT NOT NULL,
                            canonical_provider_id INTEGER NOT NULL,
                            first_seen_at INTEGER NOT NULL,
                            PRIMARY KEY (tmdb_id, content_type),
                            FOREIGN KEY(canonical_provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_tmdb_identity_content_type ON tmdb_identity(content_type)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_tmdb_identity_canonical_provider_id ON tmdb_identity(canonical_provider_id)")
                    database.execSQL(
                        """
                        INSERT OR REPLACE INTO tmdb_identity (tmdb_id, content_type, canonical_provider_id, first_seen_at)
                        SELECT tmdb_id, 'MOVIE', MIN(provider_id), 0
                        FROM movies
                        WHERE tmdb_id IS NOT NULL
                        GROUP BY tmdb_id
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        INSERT OR REPLACE INTO tmdb_identity (tmdb_id, content_type, canonical_provider_id, first_seen_at)
                        SELECT tmdb_id, 'SERIES', MIN(provider_id), 0
                        FROM series
                        WHERE tmdb_id IS NOT NULL
                        GROUP BY tmdb_id
                        """.trimIndent()
                    )
                    validateForeignKeys(database, "tmdb_identity")
                }
            }

    val MIGRATION_37_38 = object : Migration(37, 38) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE epg_sources ADD COLUMN etag TEXT DEFAULT NULL")
                    database.execSQL("ALTER TABLE epg_sources ADD COLUMN last_modified_header TEXT DEFAULT NULL")
                }
            }

    val MIGRATION_38_39 = object : Migration(38, 39) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE providers ADD COLUMN stalker_mac_address TEXT NOT NULL DEFAULT ''")
                    database.execSQL("ALTER TABLE providers ADD COLUMN stalker_device_profile TEXT NOT NULL DEFAULT ''")
                    database.execSQL("ALTER TABLE providers ADD COLUMN stalker_device_timezone TEXT NOT NULL DEFAULT ''")
                    database.execSQL("ALTER TABLE providers ADD COLUMN stalker_device_locale TEXT NOT NULL DEFAULT ''")
                    database.execSQL("DROP INDEX IF EXISTS index_providers_server_url_username")
                    database.execSQL("DROP INDEX IF EXISTS index_providers_server_url_username_stalker_mac_address")
                    database.execSQL(
                        """
                        CREATE UNIQUE INDEX IF NOT EXISTS index_providers_server_url_username_stalker_mac_address
                        ON providers(server_url, username, stalker_mac_address)
                        """.trimIndent()
                    )
                }
            }
    
            /**
             * Migration 39 → 40: Drop per-row FTS triggers.
             * The triggers (channels_ai/ad/au, movies_ai/ad/au, series_ai/ad/au) fired for every
             * individual row INSERT/DELETE/UPDATE and serialised 52k+ writes through the FTS index
             * one row at a time, causing minute-long first-sync freezes on large providers.
             * FTS is now rebuilt in bulk via INSERT INTO table_fts(table_fts) VALUES('rebuild')
             * once per sync, after each catalog transaction commits.
             */

    val MIGRATION_39_40 = object : Migration(39, 40) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("DROP TRIGGER IF EXISTS channels_ai")
                    database.execSQL("DROP TRIGGER IF EXISTS channels_ad")
                    database.execSQL("DROP TRIGGER IF EXISTS channels_au")
                    database.execSQL("DROP TRIGGER IF EXISTS movies_ai")
                    database.execSQL("DROP TRIGGER IF EXISTS movies_ad")
                    database.execSQL("DROP TRIGGER IF EXISTS movies_au")
                    database.execSQL("DROP TRIGGER IF EXISTS series_ai")
                    database.execSQL("DROP TRIGGER IF EXISTS series_ad")
                    database.execSQL("DROP TRIGGER IF EXISTS series_au")
                }
            }
    
            /**
             * Migration 40 → 41: add per-channel A/V sync override.
             * Null means the channel follows the global player default.
             */

    val MIGRATION_40_41 = object : Migration(40, 41) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE channel_preferences ADD COLUMN audio_video_offset_ms INTEGER DEFAULT NULL")
                }
            }
    
            /**
             * Migration 41 -> 42: remember playback decoder/surface combinations that fail
             * silently on a device so Auto mode can avoid repeating them.
             */

    val MIGRATION_41_42 = object : Migration(41, 42) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS playback_compatibility_records (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            device_fingerprint TEXT NOT NULL,
                            device_model TEXT NOT NULL,
                            android_sdk INTEGER NOT NULL,
                            stream_type TEXT NOT NULL,
                            video_mime_type TEXT NOT NULL,
                            resolution_bucket TEXT NOT NULL,
                            decoder_name TEXT NOT NULL,
                            surface_type TEXT NOT NULL,
                            failure_type TEXT NOT NULL DEFAULT '',
                            last_failed_at INTEGER NOT NULL DEFAULT 0,
                            last_succeeded_at INTEGER NOT NULL DEFAULT 0,
                            failure_count INTEGER NOT NULL DEFAULT 0,
    
                            success_count INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        CREATE UNIQUE INDEX IF NOT EXISTS index_playback_compatibility_records_device_fingerprint_stream_type_video_mime_type_resolution_bucket_decoder_name_surface_type
                        ON playback_compatibility_records(device_fingerprint, stream_type, video_mime_type, resolution_bucket, decoder_name, surface_type)
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        CREATE INDEX IF NOT EXISTS index_playback_compatibility_records_device_fingerprint_stream_type_video_mime_type_resolution_bucket
                        ON playback_compatibility_records(device_fingerprint, stream_type, video_mime_type, resolution_bucket)
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_playback_compatibility_records_last_failed_at ON playback_compatibility_records(last_failed_at)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_playback_compatibility_records_last_succeeded_at ON playback_compatibility_records(last_succeeded_at)")
                }
            }
    
            /**
             * Migration 42 -> 43: preserve provider-native series identifiers so Stalker
             * series details can round-trip composite portal IDs.
             */

    val MIGRATION_42_43 = object : Migration(42, 43) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE series ADD COLUMN provider_series_id TEXT")
                    database.execSQL(
                        "UPDATE series SET provider_series_id = CAST(series_id AS TEXT) WHERE provider_series_id IS NULL"
                    )
                }
            }
    
            /**
             * Migration 43 -> 44: add page-aware VOD/series category hydration metadata
             * for on-demand Stalker paging while preserving existing complete caches.
             */

    val MIGRATION_43_44 = object : Migration(43, 44) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    addPagedHydrationColumns(database, "movie_category_hydration")
                    addPagedHydrationColumns(database, "series_category_hydration")
                }
    
                private fun addPagedHydrationColumns(database: SupportSQLiteDatabase, tableName: String) {
                    database.execSQL("ALTER TABLE $tableName ADD COLUMN last_loaded_page INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE $tableName ADD COLUMN total_pages INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE $tableName ADD COLUMN is_complete INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE $tableName ADD COLUMN page_size INTEGER NOT NULL DEFAULT 0")
                    database.execSQL(
                        """
                        UPDATE $tableName
                        SET last_loaded_page = CASE
                                WHEN last_status = 'SUCCESS' THEN 1
                                ELSE 0
                            END,
                            total_pages = CASE
                                WHEN last_status = 'SUCCESS' THEN 1
                                ELSE 0
                            END,
                            is_complete = CASE
                                WHEN last_status = 'SUCCESS' THEN 1
                                ELSE 0
                            END,
                            page_size = CASE
                                WHEN last_status = 'SUCCESS' THEN item_count
                                ELSE 0
                            END
                        """.trimIndent()
                    )
                }
            }
    
            /**
             * Migration 44 -> 45: make favorite uniqueness null-safe by materializing a non-null
             * group scope key and deduping any pre-existing global favorite collisions.
             */

    val MIGRATION_44_45 = object : Migration(44, 45) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE favorites ADD COLUMN group_key INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("UPDATE favorites SET group_key = COALESCE(group_id, 0)")
                    database.execSQL(
                        """
                        UPDATE favorites
                        SET position = (
                                SELECT MIN(dupe.position)
                                FROM favorites AS dupe
                                WHERE dupe.group_id IS NULL
                                  AND dupe.provider_id = favorites.provider_id
                                  AND dupe.content_id = favorites.content_id
                                  AND dupe.content_type = favorites.content_type
                            ),
                            added_at = (
                                SELECT MIN(dupe.added_at)
                                FROM favorites AS dupe
                                WHERE dupe.group_id IS NULL
                                  AND dupe.provider_id = favorites.provider_id
                                  AND dupe.content_id = favorites.content_id
                                  AND dupe.content_type = favorites.content_type
                            )
                        WHERE favorites.group_id IS NULL
                          AND favorites.id IN (
                              SELECT MIN(id)
                              FROM favorites
                              WHERE group_id IS NULL
                              GROUP BY provider_id, content_id, content_type
                              HAVING COUNT(*) > 1
                          )
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        DELETE FROM favorites
                        WHERE group_id IS NULL
                          AND id NOT IN (
                              SELECT MIN(id)
                              FROM favorites
                              WHERE group_id IS NULL
                              GROUP BY provider_id, content_id, content_type
                          )
                        """.trimIndent()
                    )
    
                    database.execSQL("DROP INDEX IF EXISTS index_favorites_provider_id_content_id_content_type_group_id")
                    database.execSQL("DROP TABLE IF EXISTS favorites_new")
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS favorites_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            provider_id INTEGER NOT NULL,
                            content_id INTEGER NOT NULL,
                            content_type TEXT NOT NULL,
                            position INTEGER NOT NULL,
                            group_id INTEGER,
                            group_key INTEGER NOT NULL,
                            added_at INTEGER NOT NULL,
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE,
                            FOREIGN KEY(group_id) REFERENCES virtual_groups(id) ON DELETE SET NULL
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        INSERT INTO favorites_new(
                            id,
                            provider_id,
                            content_id,
                            content_type,
                            position,
                            group_id,
    
                            group_key,
                            added_at
                        )
                        SELECT
                            id,
                            provider_id,
                            content_id,
                            content_type,
                            position,
                            group_id,
                            group_key,
                            added_at
                        FROM favorites
                        """.trimIndent()
                    )
                    database.execSQL("DROP TABLE favorites")
                    database.execSQL("ALTER TABLE favorites_new RENAME TO favorites")
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_favorites_provider_id_content_id_content_type_group_key ON favorites(provider_id, content_id, content_type, group_key)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_favorites_provider_id_content_type_group_id ON favorites(provider_id, content_type, group_id)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_favorites_group_id_position ON favorites(group_id, position)"
                    )
                    validateForeignKeys(database, "favorites")
                }
            }
    
            /**
             * Migration 45 -> 46: preserve provider-native series IDs through staging by storing both
             * the raw provider ID and a non-null remote key used for staged apply matching.
             */

    val MIGRATION_45_46 = object : Migration(45, 46) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS series_import_stage_new (
                            session_id INTEGER NOT NULL,
                            provider_id INTEGER NOT NULL,
                            series_id INTEGER NOT NULL,
                            provider_series_id TEXT,
                            provider_series_key TEXT NOT NULL,
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
                            PRIMARY KEY(session_id, provider_id, provider_series_key),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        INSERT INTO series_import_stage_new (
                            session_id,
                            provider_id,
                            series_id,
                            provider_series_id,
                            provider_series_key,
                            name,
                            poster_url,
                            backdrop_url,
                            category_id,
                            category_name,
                            plot,
                            "cast",
                            director,
                            genre,
                            release_date,
                            rating,
                            tmdb_id,
                            youtube_trailer,
                            episode_run_time,
                            last_modified,
                            is_adult,
                            sync_fingerprint
                        )
                        SELECT
                            session_id,
                            provider_id,
                            series_id,
                            NULL,
                            CAST(series_id AS TEXT),
                            name,
                            poster_url,
                            backdrop_url,
                            category_id,
                            category_name,
                            plot,
                            "cast",
                            director,
                            genre,
                            release_date,
                            rating,
                            tmdb_id,
                            youtube_trailer,
                            episode_run_time,
                            last_modified,
                            is_adult,
                            sync_fingerprint
                        FROM series_import_stage
                        """.trimIndent()
                    )
                    database.execSQL("DROP TABLE series_import_stage")
                    database.execSQL("ALTER TABLE series_import_stage_new RENAME TO series_import_stage")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_series_import_stage_provider_id ON series_import_stage(provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_series_import_stage_session_id_provider_id ON series_import_stage(session_id, provider_id)")
                    validateForeignKeys(database, "series_import_stage")
                }
            }
    
            /**
             * Migration 46 -> 47: add provider-leading browse indexes so large-provider cursor pages,
             * category/rating sorts, and correlated playback-history filters avoid wide scans.
             */

    val MIGRATION_46_47 = object : Migration(46, 47) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_movies_provider_id_name_id ON movies(provider_id, name, id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_movies_provider_id_category_id_name_id ON movies(provider_id, category_id, name, id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_movies_provider_id_rating_name_id ON movies(provider_id, rating, name, id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_movies_provider_id_added_at_release_date_name_id ON movies(provider_id, added_at, release_date, name, id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_series_provider_id_name_id ON series(provider_id, name, id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_series_provider_id_category_id_name_id ON series(provider_id, category_id, name, id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_series_provider_id_rating_name_id ON series(provider_id, rating, name, id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_series_provider_id_last_modified_name_id ON series(provider_id, last_modified, name, id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_playback_history_provider_id_content_type_content_id ON playback_history(provider_id, content_type, content_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_playback_history_provider_id_content_type_last_watched_at ON playback_history(provider_id, content_type, last_watched_at)")
                }
            }
    
            /**
             * Migration 47 -> 48: create the Xtream summary index and section job state tables.
             * Existing Xtream live/movie/series rows are backfilled without deleting or remapping
             * the playable/detail tables that favorites and history already reference.
             */

    val MIGRATION_47_48 = object : Migration(47, 48) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE providers ADD COLUMN http_user_agent TEXT NOT NULL DEFAULT ''")
                    database.execSQL("ALTER TABLE providers ADD COLUMN http_headers TEXT NOT NULL DEFAULT ''")
                    database.execSQL("ALTER TABLE movies ADD COLUMN cache_state TEXT NOT NULL DEFAULT 'DETAIL_HYDRATED'")
                    database.execSQL("ALTER TABLE movies ADD COLUMN detail_hydrated_at INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE movies ADD COLUMN remote_stale_at INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE series ADD COLUMN cache_state TEXT NOT NULL DEFAULT 'DETAIL_HYDRATED'")
                    database.execSQL("ALTER TABLE series ADD COLUMN detail_hydrated_at INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE series ADD COLUMN remote_stale_at INTEGER NOT NULL DEFAULT 0")
    
                    database.execSQL(
                        """
                        UPDATE movies
                        SET cache_state = 'SUMMARY_ONLY'
                        WHERE COALESCE(plot, '') = ''
                          AND COALESCE("cast", '') = ''
                          AND COALESCE(director, '') = ''
                          AND COALESCE(genre, '') = ''
                          AND COALESCE(duration, '') = ''
                          AND duration_seconds = 0
                          AND tmdb_id IS NULL
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        UPDATE series
                        SET cache_state = 'SUMMARY_ONLY'
                        WHERE COALESCE(plot, '') = ''
                          AND COALESCE("cast", '') = ''
                          AND COALESCE(director, '') = ''
                          AND COALESCE(genre, '') = ''
                          AND COALESCE(episode_run_time, '') = ''
                          AND tmdb_id IS NULL
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        UPDATE movies
                        SET detail_hydrated_at = COALESCE(
                            (SELECT providers.last_synced_at FROM providers WHERE providers.id = movies.provider_id),
                            0
                        )
                        WHERE cache_state = 'DETAIL_HYDRATED'
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        UPDATE series
                        SET detail_hydrated_at = COALESCE(
                            (SELECT providers.last_synced_at FROM providers WHERE providers.id = series.provider_id),
                            0
                        )
                        WHERE cache_state = 'DETAIL_HYDRATED'
                        """.trimIndent()
                    )
    
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS xtream_content_index (
                            provider_id INTEGER NOT NULL,
                            content_type TEXT NOT NULL,
                            remote_id TEXT NOT NULL,
                            local_content_id INTEGER,
                            name TEXT NOT NULL,
                            category_id INTEGER,
                            category_name TEXT,
                            image_url TEXT,
                            container_extension TEXT,
                            rating REAL NOT NULL,
                            added_at INTEGER NOT NULL,
                            remote_updated_at INTEGER NOT NULL,
                            is_adult INTEGER NOT NULL,
                            indexed_at INTEGER NOT NULL,
                            detail_hydrated_at INTEGER NOT NULL,
                            stale_state TEXT NOT NULL,
                            error_state TEXT,
                            sync_fingerprint TEXT NOT NULL,
                            PRIMARY KEY(provider_id, content_type, remote_id),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_content_index_provider_id_content_type ON xtream_content_index(provider_id, content_type)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_content_index_provider_id_content_type_category_id ON xtream_content_index(provider_id, content_type, category_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_content_index_provider_id_content_type_name ON xtream_content_index(provider_id, content_type, name)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_content_index_provider_id_content_type_local_content_id ON xtream_content_index(provider_id, content_type, local_content_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_content_index_provider_id_indexed_at ON xtream_content_index(provider_id, indexed_at)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_content_index_stale_state ON xtream_content_index(stale_state)")
    
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS xtream_index_jobs (
                            provider_id INTEGER NOT NULL,
                            section TEXT NOT NULL,
                            state TEXT NOT NULL,
                            total_categories INTEGER NOT NULL,
                            completed_categories INTEGER NOT NULL,
                            next_category_index INTEGER NOT NULL,
                            failed_categories INTEGER NOT NULL,
                            indexed_rows INTEGER NOT NULL,
                            skipped_malformed_rows INTEGER NOT NULL,
                            deleted_pruned_rows INTEGER NOT NULL,
                            priority_category_id INTEGER,
                            priority_requested_at INTEGER NOT NULL,
                            last_error TEXT,
                            last_attempt_at INTEGER NOT NULL,
                            last_success_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            PRIMARY KEY(provider_id, section),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_index_jobs_provider_id ON xtream_index_jobs(provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_index_jobs_section ON xtream_index_jobs(section)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_index_jobs_state ON xtream_index_jobs(state)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_index_jobs_updated_at ON xtream_index_jobs(updated_at)")
    
                    database.execSQL(
                        """
                        INSERT OR REPLACE INTO xtream_content_index (
                            provider_id, content_type, remote_id, local_content_id, name, category_id,
                            category_name, image_url, container_extension, rating, added_at, remote_updated_at,
                            is_adult, indexed_at, detail_hydrated_at, stale_state, error_state, sync_fingerprint
                        )
                        SELECT
                            c.provider_id,
                            'LIVE',
                            CAST(c.stream_id AS TEXT),
                            c.id,
                            c.name,
                            c.category_id,
                            c.category_name,
                            c.logo_url,
                            NULL,
                            0,
                            0,
                            0,
                            c.is_adult,
                            COALESCE(p.last_synced_at, 0),
                            COALESCE(p.last_synced_at, 0),
                            'ACTIVE',
                            NULL,
                            c.sync_fingerprint
                        FROM channels c
                        JOIN providers p ON p.id = c.provider_id
                        WHERE p.type = 'XTREAM_CODES'
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
    
                        INSERT OR REPLACE INTO xtream_content_index (
                            provider_id, content_type, remote_id, local_content_id, name, category_id,
                            category_name, image_url, container_extension, rating, added_at, remote_updated_at,
                            is_adult, indexed_at, detail_hydrated_at, stale_state, error_state, sync_fingerprint
                        )
                        SELECT
                            m.provider_id,
                            'MOVIE',
                            CAST(m.stream_id AS TEXT),
                            m.id,
                            m.name,
                            m.category_id,
                            m.category_name,
                            m.poster_url,
                            m.container_extension,
                            m.rating,
                            m.added_at,
                            0,
                            m.is_adult,
                            COALESCE(p.last_synced_at, 0),
                            CASE WHEN m.cache_state = 'DETAIL_HYDRATED' THEN COALESCE(p.last_synced_at, 0) ELSE 0 END,
                            'ACTIVE',
                            NULL,
                            m.sync_fingerprint
                        FROM movies m
                        JOIN providers p ON p.id = m.provider_id
                        WHERE p.type = 'XTREAM_CODES'
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        INSERT OR REPLACE INTO xtream_content_index (
                            provider_id, content_type, remote_id, local_content_id, name, category_id,
                            category_name, image_url, container_extension, rating, added_at, remote_updated_at,
                            is_adult, indexed_at, detail_hydrated_at, stale_state, error_state, sync_fingerprint
                        )
                        SELECT
                            s.provider_id,
                            'SERIES',
                            COALESCE(s.provider_series_id, CAST(s.series_id AS TEXT)),
                            s.id,
                            s.name,
                            s.category_id,
                            s.category_name,
                            s.poster_url,
                            NULL,
                            s.rating,
                            0,
                            s.last_modified,
                            s.is_adult,
                            COALESCE(p.last_synced_at, 0),
                            CASE WHEN s.cache_state = 'DETAIL_HYDRATED' THEN COALESCE(p.last_synced_at, 0) ELSE 0 END,
                            'ACTIVE',
                            NULL,
                            s.sync_fingerprint
                        FROM series s
                        JOIN providers p ON p.id = s.provider_id
                        WHERE p.type = 'XTREAM_CODES'
                        """.trimIndent()
                    )
    
                    database.execSQL(
                        """
                        INSERT OR REPLACE INTO xtream_index_jobs (
                            provider_id, section, state, total_categories, completed_categories, next_category_index, failed_categories,
                            indexed_rows, skipped_malformed_rows, deleted_pruned_rows, priority_category_id, priority_requested_at, last_error,
                            last_attempt_at, last_success_at, updated_at
                        )
                        SELECT
                            p.id,
                            section.name,
                            CASE
                                WHEN section.name = 'LIVE' AND (
                                    COALESCE(NULLIF(sm.last_live_success, 0), NULLIF(sm.last_live_sync, 0), 0) > 0
                                    OR (SELECT COUNT(*) FROM channels c WHERE c.provider_id = p.id) > 0
                                ) THEN 'SUCCESS'
                                WHEN section.name = 'MOVIE' AND (
                                    COALESCE(NULLIF(sm.last_movie_success, 0), NULLIF(sm.last_movie_sync, 0), 0) > 0
                                    OR (SELECT COUNT(*) FROM movies m WHERE m.provider_id = p.id) > 0
                                ) THEN 'SUCCESS'
                                WHEN section.name = 'SERIES' AND (
                                    COALESCE(NULLIF(sm.last_series_success, 0), NULLIF(sm.last_series_sync, 0), 0) > 0
                                    OR (SELECT COUNT(*) FROM series s WHERE s.provider_id = p.id) > 0
                                ) THEN 'SUCCESS'
                                WHEN section.name = 'EPG' AND COALESCE(NULLIF(sm.last_epg_success, 0), NULLIF(sm.last_epg_sync, 0), 0) > 0 THEN 'SUCCESS'
                                ELSE 'IDLE'
                            END,
                            CASE section.name
                                WHEN 'LIVE' THEN (SELECT COUNT(*) FROM categories cat WHERE cat.provider_id = p.id AND cat.type = 'LIVE')
                                WHEN 'MOVIE' THEN (SELECT COUNT(*) FROM categories cat WHERE cat.provider_id = p.id AND cat.type = 'MOVIE')
                                WHEN 'SERIES' THEN (SELECT COUNT(*) FROM categories cat WHERE cat.provider_id = p.id AND cat.type = 'SERIES')
                                ELSE 0
                            END,
                            CASE
                                WHEN section.name = 'LIVE' AND (SELECT COUNT(*) FROM channels c WHERE c.provider_id = p.id) > 0
                                    THEN (SELECT COUNT(*) FROM categories cat WHERE cat.provider_id = p.id AND cat.type = 'LIVE')
                                WHEN section.name = 'MOVIE' AND (SELECT COUNT(*) FROM movies m WHERE m.provider_id = p.id) > 0
                                    THEN (SELECT COUNT(*) FROM categories cat WHERE cat.provider_id = p.id AND cat.type = 'MOVIE')
                                WHEN section.name = 'SERIES' AND (SELECT COUNT(*) FROM series s WHERE s.provider_id = p.id) > 0
                                    THEN (SELECT COUNT(*) FROM categories cat WHERE cat.provider_id = p.id AND cat.type = 'SERIES')
                                ELSE 0
                            END,
                            CASE
                                WHEN section.name = 'LIVE' AND (SELECT COUNT(*) FROM channels c WHERE c.provider_id = p.id) > 0
                                    THEN (SELECT COUNT(*) FROM categories cat WHERE cat.provider_id = p.id AND cat.type = 'LIVE')
                                WHEN section.name = 'MOVIE' AND (SELECT COUNT(*) FROM movies m WHERE m.provider_id = p.id) > 0
                                    THEN (SELECT COUNT(*) FROM categories cat WHERE cat.provider_id = p.id AND cat.type = 'MOVIE')
                                WHEN section.name = 'SERIES' AND (SELECT COUNT(*) FROM series s WHERE s.provider_id = p.id) > 0
                                    THEN (SELECT COUNT(*) FROM categories cat WHERE cat.provider_id = p.id AND cat.type = 'SERIES')
                                ELSE 0
                            END,
                            0,
                            CASE section.name
                                WHEN 'LIVE' THEN (SELECT COUNT(*) FROM channels c WHERE c.provider_id = p.id)
                                WHEN 'MOVIE' THEN (SELECT COUNT(*) FROM movies m WHERE m.provider_id = p.id)
                                WHEN 'SERIES' THEN (SELECT COUNT(*) FROM series s WHERE s.provider_id = p.id)
                                WHEN 'EPG' THEN COALESCE(sm.epg_count, 0)
                                ELSE 0
                            END,
                            0,
                            0,
                            NULL,
                            0,
                            NULL,
                            CASE section.name
                                WHEN 'LIVE' THEN COALESCE(sm.last_live_sync, 0)
                                WHEN 'MOVIE' THEN COALESCE(NULLIF(sm.last_movie_attempt, 0), sm.last_movie_sync, 0)
                                WHEN 'SERIES' THEN COALESCE(sm.last_series_sync, 0)
                                WHEN 'EPG' THEN COALESCE(sm.last_epg_sync, 0)
                                ELSE 0
                            END,
                            CASE section.name
                                WHEN 'LIVE' THEN COALESCE(NULLIF(sm.last_live_success, 0), sm.last_live_sync, 0)
                                WHEN 'MOVIE' THEN COALESCE(NULLIF(sm.last_movie_success, 0), sm.last_movie_sync, 0)
                                WHEN 'SERIES' THEN COALESCE(NULLIF(sm.last_series_success, 0), sm.last_series_sync, 0)
                                WHEN 'EPG' THEN COALESCE(NULLIF(sm.last_epg_success, 0), sm.last_epg_sync, 0)
                                ELSE 0
                            END,
                            COALESCE(p.last_synced_at, 0)
                        FROM providers p
                        CROSS JOIN (
                            SELECT 'LIVE' AS name
                            UNION ALL SELECT 'MOVIE'
                            UNION ALL SELECT 'SERIES'
                            UNION ALL SELECT 'EPG'
                        ) section
                        LEFT JOIN sync_metadata sm ON sm.provider_id = p.id
                        WHERE p.type = 'XTREAM_CODES'
                        """.trimIndent()
                    )
    
    
                    validateForeignKeys(database, "xtream_content_index", "xtream_index_jobs")
                }
            }

    val MIGRATION_48_49 = object : Migration(48, 49) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    // Schema 49 is identical to 48. HTTP profile columns shipped in schema 48.
                }
            }

    val all: List<Migration> = listOf(
        MIGRATION_24_25,
        MIGRATION_25_26,
        MIGRATION_26_27,
        MIGRATION_27_28,
        MIGRATION_28_29,
        MIGRATION_29_30,
        MIGRATION_30_31,
        MIGRATION_31_32,
        MIGRATION_32_33,
        MIGRATION_33_34,
        MIGRATION_34_35,
        MIGRATION_35_36,
        MIGRATION_36_37,
        MIGRATION_37_38,
        MIGRATION_38_39,
        MIGRATION_39_40,
        MIGRATION_40_41,
        MIGRATION_41_42,
        MIGRATION_42_43,
        MIGRATION_43_44,
        MIGRATION_44_45,
        MIGRATION_45_46,
        MIGRATION_46_47,
        MIGRATION_47_48,
        MIGRATION_48_49
    )
}
