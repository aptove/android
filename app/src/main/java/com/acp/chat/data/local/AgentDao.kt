package com.acp.chat.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.acp.chat.data.model.Agent
import com.acp.chat.data.model.ConnectionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    @Query("SELECT * FROM agents ORDER BY lastConnectedAt DESC, createdAt DESC")
    fun getAllAgents(): Flow<List<Agent>>
    
    @Query("SELECT * FROM agents ORDER BY lastConnectedAt DESC, createdAt DESC")
    suspend fun getAllAgentsOnce(): List<Agent>

    @Query("SELECT * FROM agents WHERE id = :agentId")
    suspend fun getAgentById(agentId: String): Agent?

    @Query("SELECT * FROM agents WHERE id = :agentId")
    fun observeAgent(agentId: String): Flow<Agent?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgent(agent: Agent)

    @Update
    suspend fun updateAgent(agent: Agent)

    @Delete
    suspend fun deleteAgent(agent: Agent)

    @Query("UPDATE agents SET connectionStatus = :status WHERE id = :agentId")
    suspend fun updateConnectionStatus(agentId: String, status: ConnectionStatus)

    @Query("UPDATE agents SET connectionStatus = :status, lastConnectedAt = :timestamp WHERE id = :agentId")
    suspend fun updateConnectionStatusAndTimestamp(
        agentId: String,
        status: ConnectionStatus,
        timestamp: Long
    )

    @Query("UPDATE agents SET activeSessionId = :sessionId, sessionStartedAt = :startedAt, supportsLoadSession = :supportsLoad WHERE id = :agentId")
    suspend fun updateSessionInfo(
        agentId: String,
        sessionId: String?,
        startedAt: Long?,
        supportsLoad: Boolean
    )

    @Query("UPDATE agents SET activeSessionId = NULL, sessionStartedAt = NULL WHERE id = :agentId")
    suspend fun clearSessionInfo(agentId: String)
}
