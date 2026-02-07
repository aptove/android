package com.acp.chat.data.repository

import android.content.Context
import android.util.Log
import com.acp.chat.data.local.AgentDao
import com.acp.chat.data.local.CredentialStorage
import com.acp.chat.data.model.Agent
import com.acp.chat.data.model.ConnectionConfig
import com.acp.chat.data.model.ConnectionStatus
import com.acp.chat.domain.acp.ACPClient
import com.acp.chat.service.PushTokenManager
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.model.SessionId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of connecting to an agent, includes session and whether it was resumed
 */
data class ConnectionResult(
    val session: ClientSession,
    val wasResumed: Boolean
)

@Singleton
class AgentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val agentDao: AgentDao,
    private val credentialStorage: CredentialStorage,
    private val acpClient: ACPClient
) {
    
    private val sessionCache = mutableMapOf<String, ClientSession>()
    
    companion object {
        private const val TAG = "AgentRepository"
    }
    
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
    
    /**
     * Find an agent by URL (for detecting duplicates or updates)
     */
    suspend fun findAgentByUrl(url: String): Agent? {
        val normalizedUrl = url.trimEnd('/')
        return agentDao.getAllAgentsOnce().find { agent ->
            agent.url.trimEnd('/') == normalizedUrl
        }
    }
    
    /**
     * Update credentials for an existing agent (when re-scanning QR after bridge restart)
     */
    suspend fun updateAgentCredentials(agentId: String, config: ConnectionConfig) {
        Log.d(TAG, "📝 Updating credentials for agent $agentId")
        
        // Clear cached session
        sessionCache.remove(agentId)
        
        // Disconnect if connected
        acpClient.disconnect()
        
        // Update stored credentials
        credentialStorage.saveCredentials(agentId, config)
        
        // Clear session info
        agentDao.clearSessionInfo(agentId)
        agentDao.updateConnectionStatus(agentId, ConnectionStatus.DISCONNECTED)
        
        Log.d(TAG, "✅ Credentials updated for agent $agentId")
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

    suspend fun connectToAgent(agentId: String): Result<ConnectionResult> {
        // Check if we already have a cached session - reuse it!
        val existingSession = sessionCache[agentId]
        if (existingSession != null) {
            Log.d(TAG, "✅ connectToAgent: Reusing cached session for agent $agentId")
            updateConnectionStatus(agentId, ConnectionStatus.CONNECTED)
            return Result.success(ConnectionResult(existingSession, wasResumed = true))
        }
        
        val config = credentialStorage.getCredentials(agentId)
            ?: return Result.failure(Exception("No credentials found"))
        
        val agent = agentDao.getAgentById(agentId)
        Log.d(TAG, "🔄 connectToAgent: agentId=$agentId, storedSessionId=${agent?.activeSessionId}, storedSupportsLoad=${agent?.supportsLoadSession}")

        updateConnectionStatus(agentId, ConnectionStatus.RECONNECTING)

        return try {
            // Connect to agent
            acpClient.connect(config)
            
            // Check if we have a stored session and agent supports loading
            val storedSessionId = agent?.activeSessionId
            val supportsLoad = acpClient.supportsLoadSession()
            
            Log.d(TAG, "Connecting to agent: $agentId, stored session: $storedSessionId, supports load: $supportsLoad")
            
            val session: ClientSession
            var wasResumed = false
            
            if (storedSessionId != null && supportsLoad) {
                // Try to load existing session
                Log.d(TAG, "Attempting to load session: $storedSessionId")
                val loadResult = acpClient.loadSession(SessionId(storedSessionId))
                
                if (loadResult.isSuccess) {
                    Log.d(TAG, "Successfully loaded session: $storedSessionId")
                    session = loadResult.getOrThrow()
                    wasResumed = true
                } else {
                    // Session load failed, create new session
                    Log.d(TAG, "Failed to load session, creating new: ${loadResult.exceptionOrNull()?.message}")
                    clearSessionInfo(agentId)
                    
                    val createResult = acpClient.createSession()
                    if (createResult.isFailure) {
                        updateConnectionStatus(agentId, ConnectionStatus.DISCONNECTED)
                        return Result.failure(createResult.exceptionOrNull() ?: Exception("Failed to create session"))
                    }
                    session = createResult.getOrThrow()
                    wasResumed = false
                    
                    // Store new session info
                    updateSessionInfo(agentId, session.sessionId.value, supportsLoad)
                }
            } else {
                // Create new session
                val sessionResult = acpClient.createSession()
                if (sessionResult.isFailure) {
                    updateConnectionStatus(agentId, ConnectionStatus.DISCONNECTED)
                    return Result.failure(sessionResult.exceptionOrNull() ?: Exception("Failed to create session"))
                }
                
                session = sessionResult.getOrThrow()
                wasResumed = false
                
                // Store session info for future resumption
                updateSessionInfo(agentId, session.sessionId.value, supportsLoad)
            }
            
            sessionCache[agentId] = session
            
            // Register FCM push token with bridge for background notifications
            PushTokenManager.getToken(context)?.let { fcmToken ->
                Log.d(TAG, "📲 Registering FCM push token with bridge")
                acpClient.registerPushToken(fcmToken)
            }
            
            updateConnectionStatus(agentId, ConnectionStatus.CONNECTED)
            Result.success(ConnectionResult(session, wasResumed))
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            updateConnectionStatus(agentId, ConnectionStatus.DISCONNECTED)
            Result.failure(e)
        }
    }
    
    /**
     * Update the stored session information for an agent
     */
    private suspend fun updateSessionInfo(agentId: String, sessionId: String, supportsLoad: Boolean) {
        Log.d(TAG, "📝 Storing session info for agent $agentId: sessionId=$sessionId, supportsLoad=$supportsLoad")
        agentDao.updateSessionInfo(
            agentId = agentId,
            sessionId = sessionId,
            startedAt = System.currentTimeMillis(),
            supportsLoad = supportsLoad
        )
        // Verify the update
        val updated = agentDao.getAgentById(agentId)
        Log.d(TAG, "📝 Verified stored session: activeSessionId=${updated?.activeSessionId}, supportsLoadSession=${updated?.supportsLoadSession}")
    }
    
    /**
     * Clear the session information for an agent (used when session expires or user clears)
     */
    suspend fun clearSessionInfo(agentId: String) {
        Log.d(TAG, "Clearing session info for agent: $agentId")
        agentDao.clearSessionInfo(agentId)
        sessionCache.remove(agentId)
    }

    suspend fun disconnectFromAgent(agentId: String) {
        acpClient.disconnect()
        updateConnectionStatus(agentId, ConnectionStatus.DISCONNECTED)
        sessionCache.remove(agentId)
    }
    
    fun getSession(agentId: String): ClientSession? {
        return sessionCache[agentId]
    }
    
    fun getACPClient(): ACPClient {
        return acpClient
    }
}
