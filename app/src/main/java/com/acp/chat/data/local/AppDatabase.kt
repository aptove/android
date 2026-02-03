package com.acp.chat.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.acp.chat.data.model.Agent
import com.acp.chat.data.model.Message

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Add session persistence columns
        database.execSQL("ALTER TABLE agents ADD COLUMN activeSessionId TEXT DEFAULT NULL")
        database.execSQL("ALTER TABLE agents ADD COLUMN sessionStartedAt INTEGER DEFAULT NULL")
        database.execSQL("ALTER TABLE agents ADD COLUMN supportsLoadSession INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [Agent::class, Message::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun messageDao(): MessageDao
}
