package com.ai.growsight.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PromptEntity::class, ConversationEntity::class],
    version = 12,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun promptDao(): PromptDao
    abstract fun conversationDao(): ConversationDao

    companion object {

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN planting_date INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE conversations ADD COLUMN longitude REAL")
                db.execSQL("ALTER TABLE conversations ADD COLUMN location_label TEXT")
            }
        }

        // Version 7→8: added uriHash to prompts (already shipped).
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE prompts ADD COLUMN uriHash TEXT")
            }
        }

        // Version 8→9: adds timestampMs (calendar-week lock) and isHiddenForRetake.
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE prompts ADD COLUMN timestampMs INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE prompts ADD COLUMN isHiddenForRetake INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE conversations ADD COLUMN is_voided INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}