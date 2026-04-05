package com.acp.chat.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.acp.chat.data.model.Agent
import com.acp.chat.data.model.Message
import com.acp.chat.data.model.TransportEndpoint

@Database(
    entities = [Agent::class, Message::class, TransportEndpoint::class],
    version = 6,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun messageDao(): MessageDao
    abstract fun transportEndpointDao(): TransportEndpointDao

    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add multi-transport columns to agents table
                db.execSQL("ALTER TABLE agents ADD COLUMN bridgeAgentId TEXT")
                db.execSQL("ALTER TABLE agents ADD COLUMN preferredTransport TEXT")

                // Create transport_endpoints table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS transport_endpoints (
                        endpointId TEXT NOT NULL PRIMARY KEY,
                        agentId TEXT NOT NULL,
                        transport TEXT NOT NULL,
                        url TEXT NOT NULL,
                        isActive INTEGER NOT NULL DEFAULT 0,
                        lastConnectedAt INTEGER,
                        priority INTEGER NOT NULL DEFAULT 3,
                        FOREIGN KEY (agentId) REFERENCES agents(agentId) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transport_endpoints_agentId ON transport_endpoints(agentId)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agents ADD COLUMN cwd TEXT NOT NULL DEFAULT '/'")
            }
        }
    }
}
