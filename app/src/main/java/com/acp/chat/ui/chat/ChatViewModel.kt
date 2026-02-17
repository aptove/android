package com.acp.chat.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acp.chat.data.model.Agent
import com.acp.chat.data.model.Message
import com.acp.chat.data.model.MessageSender
import com.acp.chat.data.model.MessageStatus
import com.acp.chat.data.model.MessageType
import com.acp.chat.data.model.PermissionOptionInfo
import com.acp.chat.data.repository.MessageRepository
import com.acp.chat.domain.AgentManager
import com.acp.chat.domain.usecase.SendMessageUseCase
import com.agentclientprotocol.client.ClientSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val agent: Agent? = null,
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val isSending: Boolean = false,
    val error: String? = null,
    val session: ClientSession? = null,
    val pendingApprovalOptions: Map<String, List<PermissionOptionInfo>> = emptyMap(),
    val sessionResumed: Boolean? = null, // null = unknown, true = resumed, false = new
    val showSessionIndicator: Boolean = false
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val agentManager: AgentManager,
    private val messageRepository: MessageRepository,
    private val sendMessageUseCase: SendMessageUseCase
) : ViewModel() {

    private val agentId: String = checkNotNull(savedStateHandle["agentId"])

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        loadAgent()
        loadMessages()
        connectToAgent()
        setupToolApprovalHandler()
    }

    private fun loadAgent() {
        viewModelScope.launch {
            agentManager.observeAgent(agentId)
                .collect { agent ->
                    _uiState.update { it.copy(agent = agent) }
                }
        }
    }

    private fun loadMessages() {
        viewModelScope.launch {
            messageRepository.getMessagesForAgent(agentId)
                .catch { e ->
                    _uiState.update { it.copy(error = e.message) }
                }
                .collect { messages ->
                    _uiState.update { it.copy(messages = messages) }
                }
        }
    }
    
    private fun setupToolApprovalHandler() {
        viewModelScope.launch {
            val acpClient = agentManager.getACPClient()
            acpClient.onToolApprovalRequest = { toolCallId, title, command, options ->
                android.util.Log.d("ChatViewModel", "Tool approval request: $title, options: ${options.size}")
                viewModelScope.launch {
                    val commandText = command?.let { "\n\n`$it`" } ?: ""
                    val permissionOptions = options.map {
                        com.acp.chat.data.model.PermissionOptionInfo(
                            optionId = it.optionId.value,
                            name = it.name,
                            kind = it.kind.name
                        )
                    }
                    android.util.Log.d("ChatViewModel", "Mapped permission options: ${permissionOptions.size}")
                    val approvalMessage = Message(
                        agentId = agentId,
                        text = "⚠️ **Permission Required**\n\n$title$commandText",
                        sender = MessageSender.AGENT,
                        status = MessageStatus.SENT,
                        type = MessageType.TOOL_APPROVAL_REQUEST,
                        toolCallId = toolCallId,
                        toolTitle = title,
                        toolCommand = command,
                        toolApproved = null
                    )
                    messageRepository.insertMessage(approvalMessage)
                    
                    // Store options in memory mapped to message ID
                    _uiState.update { state ->
                        val newOptionsMap = state.pendingApprovalOptions.toMutableMap()
                        newOptionsMap[approvalMessage.id] = permissionOptions
                        android.util.Log.d("ChatViewModel", "Stored options for message ${approvalMessage.id}: ${permissionOptions.map { it.name }}")
                        state.copy(pendingApprovalOptions = newOptionsMap)
                    }
                }
            }
        }
    }

    private fun connectToAgent() {
        viewModelScope.launch {
            val result = agentManager.connectToAgent(agentId)
            if (result.isSuccess) {
                val connectionResult = result.getOrNull()!!
                val session = connectionResult.session
                val wasResumed = connectionResult.wasResumed
                
                _uiState.update { 
                    it.copy(
                        session = session,
                        sessionResumed = wasResumed,
                        showSessionIndicator = true
                    )
                }
                
                // Hide session indicator after 3 seconds
                kotlinx.coroutines.delay(3000)
                _uiState.update { it.copy(showSessionIndicator = false) }
                
                // Send conversation history to the agent for context
                if (!wasResumed) {
                    sendConversationHistory(session)
                }
            } else {
                _uiState.update { it.copy(error = "Failed to connect: ${result.exceptionOrNull()?.message}") }
            }
        }
    }
    
    private suspend fun sendConversationHistory(session: ClientSession) {
        val messages = _uiState.value.messages
        if (messages.isEmpty()) return
        
        // Build a context message with recent conversation history
        val historyText = buildString {
            append("Here is our previous conversation for context:\n\n")
            messages.takeLast(20).forEach { msg ->  // Last 20 messages to avoid overwhelming context
                when (msg.sender) {
                    MessageSender.USER -> append("User: ${msg.text}\n\n")
                    MessageSender.AGENT -> append("Assistant: ${msg.text}\n\n")
                }
            }
            append("Please continue from where we left off.")
        }
        
        android.util.Log.d("ChatViewModel", "Sending conversation history with ${messages.size} messages")
        
        // Send history as a system-like message (user message with context flag)
        try {
            sendMessageUseCase(
                agentId = agentId,
                session = session,
                text = historyText
            )
        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "Failed to send conversation history", e)
        }
    }

    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        val session = _uiState.value.session

        if (text.isEmpty() || session == null) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, inputText = "") }

            val result = sendMessageUseCase(
                agentId = agentId,
                session = session,
                text = text
            )

            if (result.isFailure) {
                _uiState.update { 
                    it.copy(
                        isSending = false,
                        error = result.exceptionOrNull()?.message
                    )
                }
            } else {
                // Collect streaming messages
                result.getOrNull()?.collect { message ->
                    // Messages are already saved to DB by repository
                }
                _uiState.update { it.copy(isSending = false) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
    
    fun approveTool(messageId: String, optionId: String) {
        viewModelScope.launch {
            val message = _uiState.value.messages.find { it.id == messageId }
            val toolCallId = message?.toolCallId ?: run {
                android.util.Log.w("ChatViewModel", "⚠️ No toolCallId found for message: $messageId")
                return@launch
            }
            
            android.util.Log.d("ChatViewModel", "✅ Approving tool: $toolCallId with option: $optionId")
            
            // Update message in DB with approved status
            val isApproved = optionId.contains("allow", ignoreCase = true)
            val updatedMessage = message.copy(toolApproved = isApproved)
            messageRepository.insertMessage(updatedMessage)
            
            // Resume the continuation in ACPClient with selected option
            val acpClient = agentManager.getACPClient()
            acpClient.approveTool(toolCallId, optionId)
            
            // Clean up options from memory
            _uiState.update { state ->
                val newOptionsMap = state.pendingApprovalOptions.toMutableMap()
                newOptionsMap.remove(messageId)
                state.copy(pendingApprovalOptions = newOptionsMap)
            }
        }
    }
    
    fun rejectTool(messageId: String) {
        viewModelScope.launch {
            val message = _uiState.value.messages.find { it.id == messageId }
            val toolCallId = message?.toolCallId ?: run {
                android.util.Log.w("ChatViewModel", "⚠️ No toolCallId found for message: $messageId")
                return@launch
            }
            
            android.util.Log.d("ChatViewModel", "❌ Rejecting tool: $toolCallId")
            
            // Update message in DB with rejected status
            val updatedMessage = message.copy(toolApproved = false)
            messageRepository.insertMessage(updatedMessage)
            
            // Resume the continuation in ACPClient with cancellation
            val acpClient = agentManager.getACPClient()
            acpClient.rejectTool(toolCallId)
            
            // Clean up options from memory
            _uiState.update { state ->
                val newOptionsMap = state.pendingApprovalOptions.toMutableMap()
                newOptionsMap.remove(messageId)
                state.copy(pendingApprovalOptions = newOptionsMap)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Don't disconnect when leaving chat screen - keep the session alive
        // The connection will be reused when the user returns to this chat
        // Disconnect only happens when user explicitly requests it (e.g., swipe action on agent list)
    }
}
