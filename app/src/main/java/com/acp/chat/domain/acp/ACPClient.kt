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
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

data class ToolApprovalRequest(
    val toolCallId: String,
    val toolCall: SessionUpdate.ToolCallUpdate,
    val permissions: List<PermissionOption>
)

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
    
    // Tool approval management
    private val pendingApprovals = mutableMapOf<String, CancellableContinuation<RequestPermissionResponse>>()
    var onToolApprovalRequest: ((String, String, String?) -> Unit)? = null

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

            // Initialize handshake with terminal support
            val agentInfo = newClient.initialize(
                ClientInfo(
                    implementation = Implementation(
                        name = "ACP Chat Android",
                        version = "1.0.0"
                    ),
                    capabilities = ClientCapabilities(
                        terminal = true  // Enable terminal support
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
            
            // Create operations factory with tool approval support
            val operationsFactory = ClientOperationsFactory { sessionId, sessionResponse ->
                object : com.agentclientprotocol.common.ClientSessionOperations,
                         com.agentclientprotocol.common.TerminalOperations {
                    
                    override suspend fun requestPermissions(
                        toolCall: SessionUpdate.ToolCallUpdate,
                        permissions: List<PermissionOption>,
                        _meta: JsonElement?
                    ): RequestPermissionResponse = suspendCancellableCoroutine { continuation ->
                        val requestId = toolCall.toolCallId.value
                        pendingApprovals[requestId] = continuation
                        
                        // Notify UI on main thread
                        scope.launch(Dispatchers.Main) {
                            onToolApprovalRequest?.invoke(
                                requestId,
                                toolCall.title ?: "Tool Approval Required",
                                null
                            )
                        }
                    }
                    
                    override suspend fun terminalCreate(
                        command: String,
                        args: List<String>,
                        cwd: String?,
                        env: List<EnvVariable>,
                        outputByteLimit: ULong?,
                        _meta: JsonElement?
                    ): CreateTerminalResponse {
                        val fullCommand = "$command ${args.joinToString(" ")}"
                        
                        // For now, approve terminal commands automatically
                        // In a real implementation, this would wait for user approval
                        return CreateTerminalResponse(java.util.UUID.randomUUID().toString())
                    }
                    
                    override suspend fun terminalOutput(
                        terminalId: String,
                        _meta: JsonElement?
                    ): TerminalOutputResponse {
                        return TerminalOutputResponse("", truncated = false)
                    }
                    
                    override suspend fun terminalRelease(
                        terminalId: String,
                        _meta: JsonElement?
                    ): ReleaseTerminalResponse {
                        return ReleaseTerminalResponse()
                    }
                    
                    override suspend fun terminalWaitForExit(
                        terminalId: String,
                        _meta: JsonElement?
                    ): WaitForTerminalExitResponse {
                        return WaitForTerminalExitResponse(0u)
                    }
                    
                    override suspend fun terminalKill(
                        terminalId: String,
                        _meta: JsonElement?
                    ): KillTerminalCommandResponse {
                        return KillTerminalCommandResponse()
                    }
                    
                    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
                        // Handle notifications if needed
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

    fun approveTool(toolCallId: String) {
        val continuation = pendingApprovals.remove(toolCallId)
        continuation?.resume(
            RequestPermissionResponse(
                outcome = RequestPermissionOutcome.Selected(
                    optionId = PermissionOptionId("approve")
                )
            )
        ) {}
    }
    
    fun rejectTool(toolCallId: String) {
        val continuation = pendingApprovals.remove(toolCallId)
        continuation?.resume(
            RequestPermissionResponse(
                outcome = RequestPermissionOutcome.Cancelled
            )
        ) {}
    }

    suspend fun disconnect() {
        reconnectJob?.cancel()
        // Cancel all pending approvals
        pendingApprovals.values.forEach { it.cancel() }
        pendingApprovals.clear()
        protocol?.close()
        protocol = null
        client = null
        currentConfig = null
        _connectionState.value = ACPConnectionState.Disconnected
    }

    fun cleanup() {
        reconnectJob?.cancel()
        pendingApprovals.values.forEach { it.cancel() }
        pendingApprovals.clear()
        scope.cancel()
        httpClient.close()
    }
}
