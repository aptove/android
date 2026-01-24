package com.acp.chat.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acp.chat.data.model.Agent
import com.acp.chat.data.model.Message
import com.acp.chat.data.repository.AgentRepository
import com.acp.chat.data.repository.MessageRepository
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
    val session: ClientSession? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val agentRepository: AgentRepository,
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
    }

    private fun loadAgent() {
        viewModelScope.launch {
            agentRepository.observeAgent(agentId)
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

    private fun connectToAgent() {
        viewModelScope.launch {
            val result = agentRepository.connectToAgent(agentId)
            if (result.isSuccess) {
                _uiState.update { it.copy(session = result.getOrNull()) }
            } else {
                _uiState.update { it.copy(error = "Failed to connect: ${result.exceptionOrNull()?.message}") }
            }
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

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            agentRepository.disconnectFromAgent(agentId)
        }
    }
}
