package com.acp.chat.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.acp.chat.data.model.Agent
import com.acp.chat.data.model.Message

@Database(
    entities = [Agent::class, Message::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun messageDao(): MessageDao
}
