package com.acp.chat.domain.usecase

import com.acp.chat.data.model.Agent
import com.acp.chat.data.model.ConnectionConfig
import com.acp.chat.data.model.ConnectionStatus
import com.acp.chat.data.repository.AgentRepository
import com.acp.chat.domain.acp.ACPClient
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

class ConnectAgentUseCase @Inject constructor(
    private val agentRepository: AgentRepository,
    private val acpClient: ACPClient
) {
    
    private val json = Json {
        ignoreUnknownKeys = true
    }

    suspend operator fun invoke(qrData: String): Result<Agent> {
        return try {
            // Parse QR code data
            val config = json.decodeFromString<ConnectionConfig>(qrData)

            // Validate config - credentials optional for localhost
            if (config.url.isBlank()) {
                return Result.failure(Exception("URL is required"))
            }
            
            val isLocalhost = config.url.contains("localhost") || config.url.contains("127.0.0.1") || config.url.contains("10.0.2.2")
            if (!isLocalhost && (config.clientId.isBlank() || config.clientSecret.isBlank())) {
                return Result.failure(Exception("Credentials required for remote connections"))
            }

            // Attempt connection
            val connectResult = acpClient.connect(config)
            if (connectResult.isFailure) {
                return Result.failure(connectResult.exceptionOrNull() ?: Exception("Connection failed"))
            }

            val agentInfo = connectResult.getOrThrow()

            // Create agent entity
            val agent = Agent(
                id = UUID.randomUUID().toString(),
                name = agentInfo.implementation?.name ?: "Unknown Agent",
                description = agentInfo.implementation?.version ?: "",
                url = config.url,
                protocolVersion = config.version,
                connectionStatus = ConnectionStatus.CONNECTED,
                lastConnectedAt = System.currentTimeMillis()
            )

            // Save agent and credentials
            agentRepository.addAgent(agent, config)

            Result.success(agent)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
