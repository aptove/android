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
            connectWithConfig(config)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Connect to an agent using a pre-built ConnectionConfig.
     * Used by the pairing service after successful pairing.
     *
     * If an agent with the same URL already exists, updates its credentials instead of creating a new one.
     */
    suspend fun connectWithConfig(config: ConnectionConfig): Result<Agent> {
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

            // Check if agent already exists - if so, update credentials
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
                lastConnectedAt = System.currentTimeMillis()
            )

            agentManager.addAgent(agent, config)

            Result.success(agent)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
