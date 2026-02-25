package com.acp.chat.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.acp.chat.data.model.TransportEndpoint
import kotlinx.coroutines.flow.Flow

@Dao
interface TransportEndpointDao {

    @Query("SELECT * FROM transport_endpoints WHERE agentId = :agentId ORDER BY priority ASC")
    fun getEndpointsByAgentId(agentId: String): Flow<List<TransportEndpoint>>

    @Query("SELECT * FROM transport_endpoints WHERE agentId = :agentId ORDER BY priority ASC")
    suspend fun getEndpointsByAgentIdOnce(agentId: String): List<TransportEndpoint>

    @Query("SELECT * FROM transport_endpoints WHERE agentId = :agentId AND isActive = 1 LIMIT 1")
    suspend fun getActiveEndpoint(agentId: String): TransportEndpoint?

    @Query("SELECT * FROM transport_endpoints WHERE agentId = :agentId AND transport = :transport LIMIT 1")
    suspend fun getEndpointByTransport(agentId: String, transport: String): TransportEndpoint?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEndpoint(endpoint: TransportEndpoint)

    @Update
    suspend fun updateEndpoint(endpoint: TransportEndpoint)

    @Query("UPDATE transport_endpoints SET isActive = :isActive, lastConnectedAt = CASE WHEN :isActive = 1 THEN :timestamp ELSE lastConnectedAt END WHERE endpointId = :endpointId")
    suspend fun updateEndpointStatus(endpointId: String, isActive: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE transport_endpoints SET isActive = 0 WHERE agentId = :agentId")
    suspend fun deactivateAllEndpoints(agentId: String)

    @Query("SELECT * FROM transport_endpoints WHERE isActive = 1")
    fun getAllActiveEndpoints(): Flow<List<TransportEndpoint>>

    @Query("DELETE FROM transport_endpoints WHERE endpointId = :endpointId")
    suspend fun deleteEndpoint(endpointId: String)

    @Query("DELETE FROM transport_endpoints WHERE agentId = :agentId")
    suspend fun deleteAllEndpointsForAgent(agentId: String)
}
