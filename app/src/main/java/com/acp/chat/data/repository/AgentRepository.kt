package com.acp.chat.data.repository

import com.acp.chat.data.local.AgentDao
import com.acp.chat.data.model.Agent
import com.acp.chat.data.model.ConnectionStatus
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for Agent data persistence.
 * Handles only CRUD operations and reactive queries.
 * Business logic is in AgentManager.
 */
@Singleton
class AgentRepository @Inject constructor(
    private val agentDao: AgentDao
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

    /**
     * Find an agent by URL (for detecting duplicates or updates)
     */
    suspend fun findAgentByUrl(url: String): Agent? {
        val normalizedUrl = url.trimEnd('/')
        return getAllAgentsOnce().find { agent ->
            agent.url.trimEnd('/') == normalizedUrl
        }
    }

    // MARK: - Session Management (data only)

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

    // MARK: - Status Management (data only)

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
}
