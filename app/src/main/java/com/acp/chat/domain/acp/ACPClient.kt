package com.acp.chat.domain.acp

import android.util.Log
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
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
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
    
    private var httpClient: HttpClient? = null

    private var protocol: Protocol? = null
    private var client: Client? = null
    private var currentConfig: ConnectionConfig? = null
    private var agentCapabilities: AgentCapabilities? = null
    
    private val _connectionState = MutableStateFlow<ACPConnectionState>(ACPConnectionState.Disconnected)
    val connectionState: StateFlow<ACPConnectionState> = _connectionState.asStateFlow()
    
    // Tool approval management
    private val pendingApprovals = mutableMapOf<String, CancellableContinuation<RequestPermissionResponse>>()
    var onToolApprovalRequest: ((String, String, String?, List<PermissionOption>) -> Unit)? = null

    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    /**
     * Create an HttpClient with optional self-signed certificate support
     */
    private fun createHttpClient(config: ConnectionConfig): HttpClient {
        return HttpClient(OkHttp) {
            install(WebSockets) {
                pingInterval = 30.seconds
            }
            
            // Configure OkHttp for self-signed certificates if needed
            if (config.hasSelfSignedCert) {
                engine {
                    preconfigured = createSelfSignedTrustingOkHttpClient(config.certFingerprint)
                }
            }
        }
    }
    
    /**
     * Create an OkHttpClient that trusts a self-signed certificate with the given fingerprint
     */
    private fun createSelfSignedTrustingOkHttpClient(expectedFingerprint: String?): OkHttpClient {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // Not used for client certificates
            }
            
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) {
                    throw java.security.cert.CertificateException("No certificates in chain")
                }
                
                val serverCert = chain[0]
                val actualFingerprint = calculateFingerprint(serverCert)
                
                Log.d("ACPClient", "🔐 Server cert fingerprint: $actualFingerprint")
                Log.d("ACPClient", "🔐 Expected fingerprint: $expectedFingerprint")
                
                if (expectedFingerprint != null && 
                    actualFingerprint.equals(expectedFingerprint, ignoreCase = true)) {
                    Log.d("ACPClient", "🔐 Certificate fingerprint matches!")
                    return // Certificate is trusted
                }
                
                throw java.security.cert.CertificateException(
                    "Certificate fingerprint mismatch. Expected: $expectedFingerprint, Got: $actualFingerprint"
                )
            }
            
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
        
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true } // Allow any hostname for local development
            .build()
    }
    
    /**
     * Calculate SHA256 fingerprint of a certificate, formatted as colon-separated hex
     */
    private fun calculateFingerprint(cert: X509Certificate): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(cert.encoded)
        return digest.joinToString(":") { String.format("%02X", it) }
    }

    suspend fun connect(config: ConnectionConfig): Result<AgentInfo> = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = ACPConnectionState.Connecting
            currentConfig = config
            reconnectAttempt = 0
            
            // Close existing client if any
            httpClient?.close()
            
            // Create new HTTP client with appropriate TLS settings
            val newHttpClient = createHttpClient(config)
            httpClient = newHttpClient

            // Create protocol with WebSocket transport
            val wsUrl = config.toWebSocketUrl()
            val isLocalhost = wsUrl.contains("localhost") || wsUrl.contains("127.0.0.1") || wsUrl.contains("10.0.2.2")
            
            val newProtocol = newHttpClient.acpProtocolOnClientWebSocket(
                url = wsUrl,
                protocolOptions = ProtocolOptions()
            ) {
                // Only send Cloudflare headers for remote connections with credentials
                if (!isLocalhost && !config.clientId.isNullOrBlank() && !config.clientSecret.isNullOrBlank()) {
                    headers.append("CF-Access-Client-Id", config.clientId)
                    headers.append("CF-Access-Client-Secret", config.clientSecret)
                }
                
                // Send bridge auth token if provided
                if (!config.authToken.isNullOrBlank()) {
                    headers.append("X-Bridge-Token", config.authToken)
                }
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
            
            // Store agent capabilities for later use
            agentCapabilities = agentInfo.capabilities
            Log.d("ACPClient", "🔧 Agent capabilities: loadSession=${agentInfo.capabilities.loadSession}, sessionCapabilities=${agentInfo.capabilities.sessionCapabilities}")

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
            val operationsFactory = createOperationsFactory()
            
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
    
    /**
     * Load an existing session by ID. Only works if the agent supports loadSession capability.
     */
    suspend fun loadSession(sessionId: SessionId): Result<ClientSession> = withContext(Dispatchers.IO) {
        try {
            val currentClient = client ?: throw Exception("Not connected")
            
            // Check if agent supports loadSession
            if (agentCapabilities?.loadSession != true) {
                return@withContext Result.failure(Exception("Agent does not support loadSession"))
            }
            
            // Create operations factory with tool approval support
            val operationsFactory = createOperationsFactory()
            
            val session = currentClient.loadSession(
                sessionId = sessionId,
                sessionParameters = SessionCreationParameters(
                    cwd = "/tmp",
                    mcpServers = emptyList()
                ),
                operationsFactory = operationsFactory
            )
            
            Result.success(session)
        } catch (e: Exception) {
            Log.e("ACPClient", "Failed to load session: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Check if the agent supports loading sessions
     */
    fun supportsLoadSession(): Boolean {
        return agentCapabilities?.loadSession == true
    }
    
    private fun createOperationsFactory(): ClientOperationsFactory {
        return ClientOperationsFactory { sessionId, sessionResponse ->
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
                            null,
                            permissions
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
                            is SessionUpdate.ToolCall -> {
                                // New tool call initiated
                                val toolInfo = buildString {
                                    append("🔧 **${update.title}**\n")
                                    append("Status: ${update.status?.name ?: "started"}\n")
                                    update.rawInput?.let {
                                        append("\n📥 Input:\n```json\n$it\n```\n")
                                    }
                                    update.rawOutput?.let {
                                        append("\n📤 Output:\n```json\n$it\n```\n")
                                    }
                                }
                                emit(ACPMessage.TextChunk(toolInfo, isComplete = false))
                            }
                            is SessionUpdate.ToolCallUpdate -> {
                                // Tool call status update
                                val toolInfo = buildString {
                                    append("🔧 **${update.title ?: "Tool"}** - ${update.status?.name ?: "updated"}\n")
                                    update.rawInput?.let {
                                        append("\n📥 Input:\n```json\n$it\n```\n")
                                    }
                                    update.rawOutput?.let {
                                        append("\n📤 Output:\n```json\n$it\n```\n")
                                    }
                                }
                                emit(ACPMessage.TextChunk(toolInfo, isComplete = false))
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

    fun approveTool(toolCallId: String, optionId: String = "allow_once") {
        val continuation = pendingApprovals.remove(toolCallId)
        if (continuation != null) {
            android.util.Log.d("ACPClient", "✅ Tool approved: $toolCallId with option: $optionId")
            continuation.resume(
                RequestPermissionResponse(
                    outcome = RequestPermissionOutcome.Selected(
                        optionId = PermissionOptionId(optionId)
                    )
                )
            ) {}
        } else {
            android.util.Log.w("ACPClient", "⚠️ No pending approval found for toolCallId: $toolCallId")
        }
    }
    
    fun rejectTool(toolCallId: String) {
        val continuation = pendingApprovals.remove(toolCallId)
        if (continuation != null) {
            android.util.Log.d("ACPClient", "❌ Tool rejected: $toolCallId")
            continuation.resume(
                RequestPermissionResponse(
                    outcome = RequestPermissionOutcome.Cancelled
                )
            ) {}
        } else {
            android.util.Log.w("ACPClient", "⚠️ No pending rejection found for toolCallId: $toolCallId")
        }
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
        httpClient?.close()
    }
}
