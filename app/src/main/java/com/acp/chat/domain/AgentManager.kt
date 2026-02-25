package com.acp.chat.domain

import android.content.Context
import android.util.Log
import com.acp.chat.data.local.CredentialStorage
import com.acp.chat.data.local.TransportCredentials
import com.acp.chat.data.model.Agent
import com.acp.chat.data.model.ConnectionConfig
import com.acp.chat.data.model.ConnectionStatus
import com.acp.chat.data.model.TransportEndpoint
import com.acp.chat.data.repository.AgentRepository
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

/**
 * Manager for agent business logic and connection management.
 * Separates business logic from data persistence (AgentRepository).
 *
 * Responsibilities:
 * - Connection lifecycle (connect, disconnect)
 * - Session caching and management
 * - Credential coordination
 * - Business logic for agent operations
 *
 * Delegates data operations to AgentRepository.
 */
@Singleton
class AgentManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AgentRepository,
    private val credentialStorage: CredentialStorage,
    private val acpClient: ACPClient
) {

    private val sessionCache = mutableMapOf<String, ClientSession>()

    companion object {
        private const val TAG = "AgentManager"
    }

    // MARK: - Reactive Queries (delegate to repository)

    fun observeAgents(): Flow<List<Agent>> = repository.getAllAgents()

    fun observeAgent(agentId: String): Flow<Agent?> = repository.observeAgent(agentId)

    suspend fun getAgent(agentId: String): Agent? = repository.getAgentById(agentId)

    // MARK: - Agent CRUD (delegate to repository, coordinate credentials)

    suspend fun addAgent(agent: Agent, config: ConnectionConfig) {
        repository.addAgent(agent)
        credentialStorage.saveCredentials(agent.agentId, config)
        Log.d(TAG, "✅ Added agent: ${agent.name} (${agent.agentId})")
    }

    suspend fun updateAgent(agent: Agent) {
        repository.updateAgent(agent)
    }

    suspend fun findAgentByUrl(url: String): Agent? = repository.findAgentByUrl(url)

    suspend fun findAgentByBridgeAgentId(bridgeAgentId: String): Agent? =
        repository.findAgentByBridgeAgentId(bridgeAgentId)

    suspend fun deleteAgent(agentId: String) {
        sessionCache.remove(agentId)
        repository.deleteAgent(agentId)
        credentialStorage.deleteCredentials(agentId)
        Log.d(TAG, "🗑️ Deleted agent: $agentId")
    }

    suspend fun updateConnectionStatus(agentId: String, status: ConnectionStatus) {
        repository.updateConnectionStatus(agentId, status)
    }

    // MARK: - Transport Endpoints

    fun observeEndpoints(agentId: String): Flow<List<TransportEndpoint>> =
        repository.getEndpointsForAgent(agentId)

    fun observeAllActiveEndpoints(): Flow<List<TransportEndpoint>> =
        repository.getAllActiveEndpoints()

    /** Upsert a transport endpoint and persist its credentials. */
    suspend fun addOrUpdateTransportEndpoint(agentId: String, transport: String, config: ConnectionConfig) {
        val endpoint = repository.upsertTransportEndpoint(agentId, transport, config.url)
        credentialStorage.saveTransportCredentials(
            endpoint.endpointId,
            TransportCredentials(
                authToken = config.authToken,
                certFingerprint = config.certFingerprint,
                clientId = config.clientId,
                clientSecret = config.clientSecret
            )
        )
        Log.d(TAG, "📍 Upserted transport endpoint: $transport → ${config.url}")
    }

    suspend fun setPreferredTransport(agentId: String, transport: String?) {
        repository.updatePreferredTransport(agentId, transport)
    }

    suspend fun deleteTransportEndpoint(endpointId: String) {
        repository.deleteEndpoint(endpointId)
        credentialStorage.deleteTransportCredentials(endpointId)
    }

    // MARK: - Connection Management (business logic)

    suspend fun connectToAgent(agentId: String, configOverride: ConnectionConfig? = null): Result<ConnectionResult> {
        // Check if we already have a cached session - reuse it!
        val existingSession = sessionCache[agentId]
        if (existingSession != null) {
            Log.d(TAG, "✅ connectToAgent: Reusing cached session for agent $agentId")
            repository.updateConnectionStatus(agentId, ConnectionStatus.CONNECTED)
            return Result.success(ConnectionResult(existingSession, wasResumed = true))
        }

        val config = configOverride ?: credentialStorage.getCredentials(agentId)
            ?: return Result.failure(Exception("No credentials found"))

        val agent = repository.getAgentById(agentId)
        Log.d(TAG, "🔄 connectToAgent: agentId=$agentId, storedSessionId=${agent?.activeSessionId}, storedSupportsLoad=${agent?.supportsLoadSession}")

        repository.updateConnectionStatus(agentId, ConnectionStatus.RECONNECTING)

        return try {
            acpClient.connect(config)

            val storedSessionId = agent?.activeSessionId
            val supportsLoad = acpClient.supportsLoadSession()

            Log.d(TAG, "Connecting to agent: $agentId, stored session: $storedSessionId, supports load: $supportsLoad")

            val session: ClientSession
            var wasResumed = false

            if (storedSessionId != null && supportsLoad) {
                Log.d(TAG, "Attempting to load session: $storedSessionId")
                val loadResult = acpClient.loadSession(SessionId(storedSessionId))

                if (loadResult.isSuccess) {
                    Log.d(TAG, "Successfully loaded session: $storedSessionId")
                    session = loadResult.getOrThrow()
                    wasResumed = true
                } else {
                    Log.d(TAG, "Failed to load session, creating new: ${loadResult.exceptionOrNull()?.message}")
                    repository.clearSessionInfo(agentId)

                    val createResult = acpClient.createSession()
                    if (createResult.isFailure) {
                        repository.updateConnectionStatus(agentId, ConnectionStatus.DISCONNECTED)
                        return Result.failure(createResult.exceptionOrNull() ?: Exception("Failed to create session"))
                    }
                    session = createResult.getOrThrow()
                    wasResumed = false

                    repository.updateSessionInfo(agentId, session.sessionId.value, supportsLoad)
                }
            } else {
                val sessionResult = acpClient.createSession()
                if (sessionResult.isFailure) {
                    repository.updateConnectionStatus(agentId, ConnectionStatus.DISCONNECTED)
                    return Result.failure(sessionResult.exceptionOrNull() ?: Exception("Failed to create session"))
                }

                session = sessionResult.getOrThrow()
                wasResumed = false

                repository.updateSessionInfo(agentId, session.sessionId.value, supportsLoad)
            }

            sessionCache[agentId] = session

            PushTokenManager.getToken(context)?.let { fcmToken ->
                Log.d(TAG, "📲 Registering FCM push token with bridge")
                acpClient.registerPushToken(fcmToken)
            }

            repository.updateConnectionStatus(agentId, ConnectionStatus.CONNECTED)
            Result.success(ConnectionResult(session, wasResumed))
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            repository.updateConnectionStatus(agentId, ConnectionStatus.DISCONNECTED)
            Result.failure(e)
        }
    }

    suspend fun disconnectFromAgent(agentId: String) {
        repository.updateConnectionStatus(agentId, ConnectionStatus.DISCONNECTED)
        sessionCache.remove(agentId)
        Log.d(TAG, "🔌 Disconnected from agent: $agentId")
    }

    /**
     * Connect using transport endpoints in priority order, falling back to legacy single-URL.
     * Preferred transport (if set) is tried first.
     */
    suspend fun connectAgent(agentId: String): Result<ConnectionResult> {
        val agent = repository.getAgentById(agentId)
            ?: return Result.failure(Exception("Agent not found: $agentId"))

        val endpoints = repository.getEndpointsForAgentOnce(agentId)
            .sortedWith(compareBy(
                { if (it.transport == agent.preferredTransport) -1 else it.priority },
                { it.priority }
            ))

        if (endpoints.isEmpty()) {
            return connectToAgent(agentId)
        }

        for (endpoint in endpoints) {
            val agentCreds = credentialStorage.getCredentials(agentId)
            val transportCreds = credentialStorage.getTransportCredentials(endpoint.endpointId)
            val config = when {
                transportCreds != null -> ConnectionConfig(
                    url = endpoint.url,
                    authToken = transportCreds.authToken,
                    certFingerprint = transportCreds.certFingerprint,
                    clientId = transportCreds.clientId,
                    clientSecret = transportCreds.clientSecret,
                    protocol = agentCreds?.protocol ?: "acp",
                    version = agentCreds?.version ?: "1.0"
                )
                agentCreds != null -> agentCreds.copy(url = endpoint.url)
                else -> continue
            }

            Log.d(TAG, "🔄 Trying endpoint ${endpoint.transport} → ${endpoint.url}")
            val result = connectToAgent(agentId, configOverride = config)
            if (result.isSuccess) {
                repository.deactivateAllEndpoints(agentId)
                repository.updateEndpointStatus(endpoint.endpointId, true)
                return result
            }
            repository.updateEndpointStatus(endpoint.endpointId, false)
            Log.w(TAG, "⚠️ Endpoint ${endpoint.transport} failed, trying next")
        }

        repository.updateConnectionStatus(agentId, ConnectionStatus.DISCONNECTED)
        return Result.failure(Exception("All transport endpoints failed for agent $agentId"))
    }

    /**
     * Update credentials for an existing agent (when re-scanning QR after bridge restart)
     */
    suspend fun updateAgentCredentials(agentId: String, config: ConnectionConfig) {
        Log.d(TAG, "📝 Updating credentials for agent $agentId")
        sessionCache.remove(agentId)
        acpClient.disconnect()
        credentialStorage.saveCredentials(agentId, config)
        repository.clearSessionInfo(agentId)
        repository.updateConnectionStatus(agentId, ConnectionStatus.DISCONNECTED)
        Log.d(TAG, "✅ Credentials updated for agent $agentId")
    }

    /**
     * Clear session for an agent (e.g., "Clear Session" button)
     */
    suspend fun clearSession(agentId: String) {
        Log.d(TAG, "Clearing session for agent: $agentId")
        repository.clearSessionInfo(agentId)
        sessionCache.remove(agentId)
    }

    fun getSession(agentId: String): ClientSession? = sessionCache[agentId]

    fun getACPClient(): ACPClient = acpClient

    fun getCredentials(agentId: String): ConnectionConfig? =
        credentialStorage.getCredentials(agentId)
}
