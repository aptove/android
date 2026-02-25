package com.acp.chat.data.repository

import com.acp.chat.data.local.AgentDao
import com.acp.chat.data.local.TransportEndpointDao
import com.acp.chat.data.model.Agent
import com.acp.chat.data.model.ConnectionStatus
import com.acp.chat.data.model.TransportEndpoint
import com.acp.chat.data.model.TransportPriority
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentRepository @Inject constructor(
    private val agentDao: AgentDao,
    private val transportEndpointDao: TransportEndpointDao
) {

    // MARK: - Reactive Queries

    fun getAllAgents(): Flow<List<Agent>> = agentDao.getAllAgents()

    fun observeAgent(agentId: String): Flow<Agent?> = agentDao.observeAgent(agentId)

    // MARK: - Synchronous Queries

    suspend fun getAgentById(agentId: String): Agent? = agentDao.getAgentById(agentId)

    suspend fun getAllAgentsOnce(): List<Agent> = agentDao.getAllAgentsOnce()

    // MARK: - CRUD Operations

    suspend fun addAgent(agent: Agent) {
        agentDao.insertAgent(agent)
    }

    suspend fun updateAgent(agent: Agent) {
        agentDao.updateAgent(agent)
    }

    suspend fun deleteAgent(agentId: String) {
        val agent = agentDao.getAgentById(agentId) ?: return
        agentDao.deleteAgent(agent)
    }

    suspend fun findAgentByUrl(url: String): Agent? {
        val normalizedUrl = url.trimEnd('/')
        return getAllAgentsOnce().find { agent ->
            agent.url.trimEnd('/') == normalizedUrl
        }
    }

    suspend fun findAgentByBridgeAgentId(bridgeAgentId: String): Agent? =
        agentDao.getAgentByBridgeAgentId(bridgeAgentId)

    // MARK: - Session Management

    suspend fun updateSessionInfo(agentId: String, sessionId: String, supportsLoad: Boolean) {
        agentDao.updateSessionInfo(
            agentId = agentId,
            sessionId = sessionId,
            startedAt = System.currentTimeMillis(),
            supportsLoad = supportsLoad
        )
    }

    suspend fun clearSessionInfo(agentId: String) {
        agentDao.clearSessionInfo(agentId)
    }

    // MARK: - Status Management

    suspend fun updateConnectionStatus(agentId: String, status: ConnectionStatus) {
        if (status == ConnectionStatus.CONNECTED) {
            agentDao.updateConnectionStatusAndTimestamp(
                agentId,
                status,
                System.currentTimeMillis()
            )
        } else {
            agentDao.updateConnectionStatus(agentId, status)
        }
    }

    // MARK: - Transport Endpoints

    fun getEndpointsForAgent(agentId: String): Flow<List<TransportEndpoint>> =
        transportEndpointDao.getEndpointsByAgentId(agentId)

    fun getAllActiveEndpoints(): Flow<List<TransportEndpoint>> =
        transportEndpointDao.getAllActiveEndpoints()

    suspend fun getEndpointsForAgentOnce(agentId: String): List<TransportEndpoint> =
        transportEndpointDao.getEndpointsByAgentIdOnce(agentId)

    suspend fun getActiveEndpoint(agentId: String): TransportEndpoint? =
        transportEndpointDao.getActiveEndpoint(agentId)

    /** Add or update an endpoint for the given transport type. Returns the upserted endpoint. */
    suspend fun upsertTransportEndpoint(
        agentId: String,
        transport: String,
        url: String
    ): TransportEndpoint {
        val existing = transportEndpointDao.getEndpointByTransport(agentId, transport)
        return if (existing != null) {
            val updated = existing.copy(url = url, priority = TransportPriority.forTransport(transport))
            transportEndpointDao.updateEndpoint(updated)
            updated
        } else {
            val endpoint = TransportEndpoint(
                endpointId = UUID.randomUUID().toString(),
                agentId = agentId,
                transport = transport,
                url = url,
                priority = TransportPriority.forTransport(transport)
            )
            transportEndpointDao.insertEndpoint(endpoint)
            endpoint
        }
    }

    suspend fun updateEndpointStatus(endpointId: String, isActive: Boolean) {
        transportEndpointDao.updateEndpointStatus(endpointId, isActive)
    }

    suspend fun deactivateAllEndpoints(agentId: String) {
        transportEndpointDao.deactivateAllEndpoints(agentId)
    }

    suspend fun deleteEndpoint(endpointId: String) {
        transportEndpointDao.deleteEndpoint(endpointId)
    }

    suspend fun updatePreferredTransport(agentId: String, transport: String?) {
        agentDao.updatePreferredTransport(agentId, transport)
    }
}
