package com.ai.growsight.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [PromptEntity::class, ConversationEntity::class],
    version = 6, // bump version since schema changed
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun promptDao(): PromptDao
    abstract fun conversationDao(): ConversationDao
}