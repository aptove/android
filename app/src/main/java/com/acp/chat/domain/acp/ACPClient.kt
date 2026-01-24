package com.acp.chat.domain.acp

import com.acp.chat.data.model.ConnectionConfig
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.transport.acpProtocolOnClientWebSocket
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

@Singleton
class ACPClient @Inject constructor() {
    
    private val httpClient = HttpClient(OkHttp) {
        install(WebSockets) {
            pingInterval = 30.seconds
            maxFrameSize = Long.MAX_VALUE
        }
    }

    private var protocol: Protocol? = null
    private var client: Client? = null
    private var currentConfig: ConnectionConfig? = null
    
    private val _connectionState = MutableStateFlow<ACPConnectionState>(ACPConnectionState.Disconnected)
    val connectionState: StateFlow<ACPConnectionState> = _connectionState.asStateFlow()

    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun connect(config: ConnectionConfig): Result<AgentInfo> = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = ACPConnectionState.Connecting
            currentConfig = config
            reconnectAttempt = 0

            // Create protocol with WebSocket transport
            val newProtocol = httpClient.acpProtocolOnClientWebSocket(
                url = config.toWebSocketUrl(),
                protocolOptions = ProtocolOptions()
            ) {
                headers.append("CF-Access-Client-Id", config.clientId)
                headers.append("CF-Access-Client-Secret", config.clientSecret)
            }

            protocol = newProtocol
            
            // Create ACP client
            val newClient = Client(newProtocol)
            client = newClient

            // Start the protocol
            newProtocol.start()

            // Initialize handshake
            val agentInfo = newClient.initialize(
                ClientInfo(
                    implementation = Implementation(
                        name = "ACP Chat Android",
                        version = "1.0.0"
                    )
                )
            )

            _connectionState.value = ACPConnectionState.Connected(agentInfo, "")

            Result.success(agentInfo)
        } catch (e: Exception) {
            _connectionState.value = ACPConnectionState.Error(
                message = "Failed to connect: ${e.message}",
                cause = e
            )
            Result.failure(e)
        }
    }

    private fun handleDisconnect(error: Throwable?) {
        protocol = null
        client = null
        
        if (reconnectAttempt < 5) {
            _connectionState.value = ACPConnectionState.Reconnecting
            attemptReconnect()
        } else {
            _connectionState.value = ACPConnectionState.Error(
                message = "Connection lost",
                cause = error
            )
        }
    }

    private fun attemptReconnect() {
        reconnectJob?.cancel()
        val config = currentConfig ?: return
        
        reconnectJob = scope.launch {
            val delay = (2.0.pow(reconnectAttempt).toLong() * 1000).coerceAtMost(16000)
            delay(delay)
            reconnectAttempt++
            connect(config)
        }
    }

    suspend fun createSession(): Result<ClientSession> = withContext(Dispatchers.IO) {
        try {
            val currentClient = client ?: throw Exception("Not connected")
            
            // Create operations factory - returns a ClientSessionOperations implementation
            val operationsFactory = ClientOperationsFactory { sessionId, sessionResponse ->
                object : com.agentclientprotocol.common.ClientSessionOperations {
                    override suspend fun requestPermissions(
                        toolCall: SessionUpdate.ToolCallUpdate,
                        permissions: List<PermissionOption>,
                        _meta: kotlinx.serialization.json.JsonElement?
                    ): RequestPermissionResponse {
                        // For simple chat, cancel all permission requests
                        return RequestPermissionResponse(
                            outcome = RequestPermissionOutcome.Cancelled
                        )
                    }
                    
                    override suspend fun notify(notification: SessionUpdate, _meta: kotlinx.serialization.json.JsonElement?) {
                        // Handle notifications if needed - for now just log
                    }
                }
            }
            
            val session = currentClient.newSession(
                SessionCreationParameters(
                    cwd = "/tmp",  // Current working directory for the session
                    mcpServers = emptyList() // No MCP servers for simple chat
                ),
                operationsFactory
            )
            
            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendMessage(session: ClientSession, text: String): Flow<ACPMessage> = flow {
        // Create content blocks from text
        val contentBlocks = listOf(
            ContentBlock.Text(text = text)
        )

        // Send prompt and collect events
        session.prompt(contentBlocks)
            .collect { event ->
                when (event) {
                    is Event.SessionUpdateEvent -> {
                        when (val update = event.update) {
                            is SessionUpdate.AgentMessageChunk -> {
                                // Extract text from ContentBlock
                                val textContent = update.content
                                when (textContent) {
                                    is ContentBlock.Text -> {
                                        emit(ACPMessage.TextChunk(textContent.text, isComplete = false))
                                    }
                                    else -> {
                                        // Handle other content types if needed
                                    }
                                }
                            }
                            is SessionUpdate.AgentThoughtChunk -> {
                                // Could handle thought display
                            }
                            else -> {
                                // Handle other update types
                            }
                        }
                    }
                    is Event.PromptResponseEvent -> {
                        // Message complete
                        emit(ACPMessage.Complete)
                    }
                }
            }
    }.flowOn(Dispatchers.IO)

    suspend fun disconnect() {
        reconnectJob?.cancel()
        protocol?.close()
        protocol = null
        client = null
        currentConfig = null
        _connectionState.value = ACPConnectionState.Disconnected
    }

    fun cleanup() {
        reconnectJob?.cancel()
        scope.cancel()
        httpClient.close()
    }
}
