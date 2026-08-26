package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object FeatureMigrationsV75To76 {
    val MIGRATION_75_76 = object : Migration(75, 76) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS m3u_classification_overrides (
                    provider_id INTEGER NOT NULL,
                    source_key TEXT NOT NULL,
                    stream_id INTEGER NOT NULL,
                    target_type TEXT NOT NULL,
                    group_key TEXT NOT NULL,
                    series_key TEXT,
                    series_name TEXT,
                    season_number INTEGER,
                    episode_number INTEGER,
                    episode_title TEXT,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(provider_id, source_key),
                    FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_m3u_classification_overrides_provider_id " +
                    "ON m3u_classification_overrides(provider_id)"
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS m3u_category_classification_rules (
                    provider_id INTEGER NOT NULL,
                    group_key TEXT NOT NULL,
                    target_type TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(provider_id, group_key),
                    FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_m3u_category_classification_rules_provider_id " +
                    "ON m3u_category_classification_rules(provider_id)"
            )
        }
    }
}
