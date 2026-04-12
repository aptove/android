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
import com.acp.chat.data.repository.MessageRepository
import com.acp.chat.domain.acp.ACPClient
import com.acp.chat.domain.acp.ACPConnectionState
import com.acp.chat.service.PushTokenManager
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.model.SessionId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
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
    private val messageRepository: MessageRepository,
    private val credentialStorage: CredentialStorage,
    private val acpClient: ACPClient
) {

    private val sessionCache = mutableMapOf<String, ClientSession>()

    /**
     * Per-agent mutex preventing duplicate concurrent connection attempts.
     * ConcurrentHashMap.computeIfAbsent is atomic, so getMutex() is safe to
     * call from multiple coroutines without additional locking.
     */
    private val agentMutexes = ConcurrentHashMap<String, Mutex>()
    private fun getMutex(agentId: String): Mutex =
        agentMutexes.computeIfAbsent(agentId) { Mutex() }

    /** The agent currently using ACPClient's WebSocket connection. */
    @Volatile private var currentAgentId: String? = null

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "AgentManager"
    }

    init {
        // Observe ACPClient connection state. When the WebSocket closes unexpectedly
        // (e.g. network switch), clear the stale session and immediately retry via all
        // available transports so the app switches to the working one automatically.
        managerScope.launch {
            acpClient.connectionState.collect { state ->
                if (state is ACPConnectionState.Disconnected) {
                    val agentId = currentAgentId ?: return@collect
                    Log.w(TAG, "🔌 Transport closed unexpectedly for $agentId — retrying all transports")
                    currentAgentId = null
                    sessionCache.remove(agentId)
                    repository.updateConnectionStatus(agentId, ConnectionStatus.DISCONNECTED)
                    managerScope.launch { connectAgent(agentId) }
                }
            }
        }
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
        // Update cwd from the pairing response
        repository.getAgentById(agentId)?.let { agent ->
            repository.updateAgent(agent.copy(cwd = config.cwd))
        }
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

            // Track which agent owns the current WebSocket so the connectionState
            // observer can trigger multi-transport reconnect on unexpected close.
            currentAgentId = agentId

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
        // Clear currentAgentId first so the connectionState observer does not
        // trigger an automatic reconnect for this intentional disconnect.
        if (currentAgentId == agentId) currentAgentId = null
        repository.updateConnectionStatus(agentId, ConnectionStatus.DISCONNECTED)
        sessionCache.remove(agentId)
        Log.d(TAG, "🔌 Disconnected from agent: $agentId")
    }

    /**
     * Connect using transport endpoints in priority order, falling back to legacy single-URL.
     * Preferred transport (if set) is tried first.
     *
     * A per-agent [Mutex] prevents duplicate concurrent connection attempts: if a connection
     * is already in progress for [agentId], this call returns immediately with failure so the
     * caller (auto-connect / background-retry) can safely skip it without opening a second
     * WebSocket to the bridge.
     */
    suspend fun connectAgent(agentId: String): Result<ConnectionResult> {
        val mutex = getMutex(agentId)
        // tryLock() returns false if another coroutine already holds the lock for this agent.
        if (!mutex.tryLock()) {
            Log.d(TAG, "📱 connectAgent: Already connecting to $agentId, skipping duplicate")
            return Result.failure(Exception("Connection already in progress for $agentId"))
        }
        try {
            return connectAgentInternal(agentId)
        } finally {
            mutex.unlock()
        }
    }

    /** Internal implementation called only while holding the per-agent mutex. */
    private suspend fun connectAgentInternal(agentId: String): Result<ConnectionResult> {
        // If a session is already cached, skip the full reconnect.
        sessionCache[agentId]?.let {
            Log.d(TAG, "📱 connectAgentInternal: Agent $agentId already connected, skipping")
            repository.updateConnectionStatus(agentId, ConnectionStatus.CONNECTED)
            return Result.success(ConnectionResult(it, wasResumed = true))
        }

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
        if (currentAgentId == agentId) currentAgentId = null
        sessionCache.remove(agentId)
        acpClient.disconnect()
        credentialStorage.saveCredentials(agentId, config)
        // Update cwd from the new pairing response
        repository.getAgentById(agentId)?.let { agent ->
            repository.updateAgent(agent.copy(cwd = config.cwd))
        }
        repository.clearSessionInfo(agentId)
        repository.updateConnectionStatus(agentId, ConnectionStatus.DISCONNECTED)
        messageRepository.deleteAllMessagesForAgent(agentId)
        Log.d(TAG, "✅ Credentials updated for agent $agentId")
    }

    /**
     * Clear session for an agent (e.g., "Clear Session" button)
     */
    suspend fun clearSession(agentId: String) {
        Log.d(TAG, "Clearing session for agent: $agentId")
        repository.clearSessionInfo(agentId)
        sessionCache.remove(agentId)
        messageRepository.deleteAllMessagesForAgent(agentId)
    }

    fun getSession(agentId: String): ClientSession? = sessionCache[agentId]

    fun getACPClient(): ACPClient = acpClient

    fun getCredentials(agentId: String): ConnectionConfig? =
        credentialStorage.getCredentials(agentId)
}
