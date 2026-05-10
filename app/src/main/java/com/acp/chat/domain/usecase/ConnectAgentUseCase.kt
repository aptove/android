package com.acp.chat.domain.usecase

import android.util.Log
import com.acp.chat.data.model.Agent
import com.acp.chat.data.model.ConnectionConfig
import com.acp.chat.data.model.ConnectionStatus
import com.acp.chat.domain.AgentManager
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

private const val TAG = "ConnectAgentUseCase"

class ConnectAgentUseCase @Inject constructor(
    private val agentManager: AgentManager
) {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    /**
     * Connect to an agent using QR code data (JSON format).
     */
    suspend operator fun invoke(qrData: String): Result<Agent> {
        return try {
            val config = json.decodeFromString<ConnectionConfig>(qrData)
            // Infer transport from config fields (clientId present → Cloudflare static JSON QR)
            val transport = if (!config.clientId.isNullOrBlank()) "cloudflare" else "local"
            connectWithConfig(config, bridgeAgentId = config.agentId, transport = transport)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Connect to an agent using a pre-built ConnectionConfig.
     * Used by the pairing service after successful pairing.
     *
     * Deduplication order:
     * 1. If bridgeAgentId matches an existing agent → add/update transport endpoint only.
     * 2. If URL matches an existing agent → update credentials.
     * 3. Otherwise → create new agent.
     */
    suspend fun connectWithConfig(
        config: ConnectionConfig,
        bridgeAgentId: String? = null,
        transport: String = "local"
    ): Result<Agent> {
        return try {
            if (config.url.isBlank()) {
                return Result.failure(Exception("URL is required"))
            }

            val isLocalhost = config.url.contains("localhost") || config.url.contains("127.0.0.1") || config.url.contains("10.0.2.2")
            val hasAuth = !config.authToken.isNullOrBlank() ||
                         (!config.clientId.isNullOrBlank() && !config.clientSecret.isNullOrBlank())

            if (!isLocalhost && !hasAuth) {
                return Result.failure(Exception("Authentication required for remote connections"))
            }

            // 1. Dedup by stable bridge agent ID (multi-transport)
            if (!bridgeAgentId.isNullOrBlank()) {
                val existingByBridgeId = agentManager.findAgentByBridgeAgentId(bridgeAgentId)
                if (existingByBridgeId != null) {
                    val agentId = existingByBridgeId.agentId
                    Log.d(TAG, "Bridge agent $bridgeAgentId already registered as $agentId, adding transport endpoint")
                    agentManager.addOrUpdateTransportEndpoint(agentId, transport, config)
                    // User explicitly scanned this transport — make it preferred and force a
                    // fresh reconnect so the correct endpoint becomes active and the UI reflects it.
                    agentManager.setPreferredTransport(agentId, transport)
                    agentManager.disconnectFromAgent(agentId)
                    agentManager.getACPClient().disconnect()
                    val connectResult = agentManager.connectAgent(agentId)
                    return if (connectResult.isSuccess) {
                        agentManager.updateConnectionStatus(agentId, ConnectionStatus.CONNECTED)
                        Result.success(existingByBridgeId.copy(connectionStatus = ConnectionStatus.CONNECTED))
                    } else {
                        Result.failure(connectResult.exceptionOrNull() ?: Exception("Connection failed"))
                    }
                }
            }

            // 2. Dedup by URL (legacy/same-transport re-scan)
            val existingAgent = agentManager.findAgentByUrl(config.url)
            if (existingAgent != null) {
                Log.d(TAG, "Agent exists for URL ${config.url}, updating credentials for ${existingAgent.agentId}")
                agentManager.updateAgentCredentials(existingAgent.agentId, config)

                val connectResult = agentManager.getACPClient().connect(config)
                if (connectResult.isFailure) {
                    return Result.failure(connectResult.exceptionOrNull() ?: Exception("Connection failed after credential update"))
                }

                agentManager.updateConnectionStatus(existingAgent.agentId, ConnectionStatus.CONNECTED)

                return Result.success(existingAgent.copy(connectionStatus = ConnectionStatus.CONNECTED))
            }

            // Attempt connection for new agent
            val connectResult = agentManager.getACPClient().connect(config)
            if (connectResult.isFailure) {
                return Result.failure(connectResult.exceptionOrNull() ?: Exception("Connection failed"))
            }

            val agentInfo = connectResult.getOrThrow()

            val agent = Agent(
                agentId = UUID.randomUUID().toString(),
                name = agentInfo.implementation?.name ?: "Unknown Agent",
                description = agentInfo.implementation?.version ?: "",
                url = config.url,
                protocolVersion = config.version,
                connectionStatus = ConnectionStatus.CONNECTED,
                lastConnectedAt = System.currentTimeMillis(),
                bridgeAgentId = bridgeAgentId,
                cwd = config.cwd
            )

            agentManager.addAgent(agent, config)

            if (!bridgeAgentId.isNullOrBlank()) {
                agentManager.addOrUpdateTransportEndpoint(agent.agentId, transport, config)
            }

            Result.success(agent)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
