package com.acp.chat.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
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
import com.agentclientprotocol.model.ContentBlock
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
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
    val showSessionIndicator: Boolean = false,
    val isVoiceCorrectionPending: Boolean = false,
    val voiceCorrectedText: String? = null,
    val selectedImageUris: List<Uri> = emptyList(),
    val imageUrisByMessageId: Map<String, List<Uri>> = emptyMap(),
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val agentManager: AgentManager,
    private val messageRepository: MessageRepository,
    private val sendMessageUseCase: SendMessageUseCase,
    @ApplicationContext private val context: Context
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

    fun onImagesSelected(uris: List<Uri>) {
        _uiState.update { it.copy(selectedImageUris = it.selectedImageUris + uris) }
    }

    fun removeImage(index: Int) {
        _uiState.update { state ->
            val updated = state.selectedImageUris.toMutableList().also { it.removeAt(index) }
            state.copy(selectedImageUris = updated)
        }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        val selectedUris = _uiState.value.selectedImageUris
        val session = _uiState.value.session

        if ((text.isEmpty() && selectedUris.isEmpty()) || session == null) return

        val userMsgId = UUID.randomUUID().toString()

        viewModelScope.launch {
            // Convert URIs to base64 JPEG in IO
            val imageBlocks = withContext(Dispatchers.IO) {
                selectedUris.mapNotNull { uri ->
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri) ?: return@mapNotNull null
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream.close()
                        val outputStream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                        val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                        ContentBlock.Image(data = base64, mimeType = "image/jpeg")
                    } catch (e: Exception) {
                        android.util.Log.w("ChatViewModel", "Failed to encode image: ${e.message}")
                        null
                    }
                }
            }

            // Save URI mapping for in-session display before clearing
            if (selectedUris.isNotEmpty()) {
                _uiState.update { state ->
                    state.copy(imageUrisByMessageId = state.imageUrisByMessageId + (userMsgId to selectedUris))
                }
            }

            _uiState.update { it.copy(isSending = true, inputText = "", selectedImageUris = emptyList()) }

            val result = sendMessageUseCase(
                agentId = agentId,
                session = session,
                text = text,
                images = imageBlocks,
                userMessageId = userMsgId
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

    fun sendVoiceCorrectionRequest(rawTranscript: String) {
        val session = _uiState.value.session ?: run {
            _uiState.update { it.copy(voiceCorrectedText = rawTranscript) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isVoiceCorrectionPending = true) }
            val correctionJson = buildCorrectionJson(rawTranscript)
            var accumulated = ""
            try {
                agentManager.getACPClient()
                    .sendMessage(session, listOf(ContentBlock.Text(correctionJson)))
                    .collect { msg ->
                        when (msg) {
                            is com.acp.chat.domain.acp.ACPMessage.TextChunk -> accumulated += msg.text
                            is com.acp.chat.domain.acp.ACPMessage.Complete -> { /* done */ }
                            is com.acp.chat.domain.acp.ACPMessage.Error -> throw Exception(msg.message)
                        }
                    }
                val corrected = parseCorrectedText(accumulated) ?: rawTranscript
                _uiState.update { it.copy(voiceCorrectedText = corrected, isVoiceCorrectionPending = false) }
            } catch (e: Exception) {
                android.util.Log.w("ChatViewModel", "Voice correction failed, using raw transcript", e)
                _uiState.update { it.copy(voiceCorrectedText = rawTranscript, isVoiceCorrectionPending = false) }
            }
        }
    }

    fun clearVoiceCorrectedText() {
        _uiState.update { it.copy(voiceCorrectedText = null) }
    }

    private fun buildCorrectionJson(rawTranscript: String): String {
        val escaped = rawTranscript
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return """{"type":"voice_correction_request","version":"1.0","instructions":"Fix transcription errors, punctuation, and grammar. Return ONLY valid JSON with a single field: {\"corrected_text\": \"...\"}","raw_transcript":"$escaped"}"""
    }

    private fun parseCorrectedText(json: String): String? {
        return try {
            val obj = org.json.JSONObject(json)
            obj.optString("corrected_text").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Don't disconnect when leaving chat screen - keep the session alive
        // The connection will be reused when the user returns to this chat
        // Disconnect only happens when user explicitly requests it (e.g., swipe action on agent list)
    }
}
