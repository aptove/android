package com.acp.chat.data.repository

import com.acp.chat.data.local.AgentDao
import com.acp.chat.data.local.CredentialStorage
import com.acp.chat.data.model.Agent
import com.acp.chat.data.model.ConnectionConfig
import com.acp.chat.data.model.ConnectionStatus
import com.acp.chat.domain.acp.ACPClient
import com.agentclientprotocol.client.ClientSession
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentRepository @Inject constructor(
    private val agentDao: AgentDao,
    private val credentialStorage: CredentialStorage,
    private val acpClient: ACPClient
) {
    
    private val sessionCache = mutableMapOf<String, ClientSession>()
    
    fun getAllAgents(): Flow<List<Agent>> = agentDao.getAllAgents()

    suspend fun getAgentById(agentId: String): Agent? = agentDao.getAgentById(agentId)

    fun observeAgent(agentId: String): Flow<Agent?> = agentDao.observeAgent(agentId)

    suspend fun addAgent(agent: Agent, config: ConnectionConfig) {
        agentDao.insertAgent(agent)
        credentialStorage.saveCredentials(agent.id, config)
    }

    suspend fun updateAgent(agent: Agent) {
        agentDao.updateAgent(agent)
    }

    suspend fun deleteAgent(agentId: String) {
        val agent = agentDao.getAgentById(agentId) ?: return
        agentDao.deleteAgent(agent)
        credentialStorage.deleteCredentials(agentId)
        sessionCache.remove(agentId)
    }

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

    fun getCredentials(agentId: String): ConnectionConfig? {
        return credentialStorage.getCredentials(agentId)
    }

    suspend fun connectToAgent(agentId: String): Result<ClientSession> {
        val config = credentialStorage.getCredentials(agentId)
            ?: return Result.failure(Exception("No credentials found"))

        updateConnectionStatus(agentId, ConnectionStatus.RECONNECTING)

        return try {
            // Connect to agent
            acpClient.connect(config)
            
            // Create session
            val sessionResult = acpClient.createSession()
            if (sessionResult.isFailure) {
                updateConnectionStatus(agentId, ConnectionStatus.DISCONNECTED)
                return Result.failure(sessionResult.exceptionOrNull() ?: Exception("Failed to create session"))
            }
            
            val session = sessionResult.getOrThrow()
            sessionCache[agentId] = session
            
            updateConnectionStatus(agentId, ConnectionStatus.CONNECTED)
            Result.success(session)
        } catch (e: Exception) {
            updateConnectionStatus(agentId, ConnectionStatus.DISCONNECTED)
            Result.failure(e)
        }
    }

    suspend fun disconnectFromAgent(agentId: String) {
        acpClient.disconnect()
        updateConnectionStatus(agentId, ConnectionStatus.DISCONNECTED)
        sessionCache.remove(agentId)
    }
    
    fun getSession(agentId: String): ClientSession? {
        return sessionCache[agentId]
    }
}
