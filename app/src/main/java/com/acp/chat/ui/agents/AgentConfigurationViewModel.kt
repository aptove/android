package com.acp.chat.ui.agents

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acp.chat.data.model.Agent
import com.acp.chat.data.repository.AgentRepository
import com.acp.chat.data.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AgentConfigurationUiState(
    val agent: Agent? = null,
    val messageCount: Int = 0,
    val isLoading: Boolean = true,
    val isClearingSession: Boolean = false,
    val isDeletingAgent: Boolean = false,
    val sessionCleared: Boolean = false,
    val agentDeleted: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AgentConfigurationViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val messageRepository: MessageRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val agentId: String = savedStateHandle["agentId"] ?: error("agentId required")

    private val _uiState = MutableStateFlow(AgentConfigurationUiState())
    val uiState: StateFlow<AgentConfigurationUiState> = _uiState.asStateFlow()

    init {
        loadAgentDetails()
    }

    private fun loadAgentDetails() {
        viewModelScope.launch {
            agentRepository.observeAgent(agentId)
                .catch { e ->
                    _uiState.update { it.copy(error = e.message, isLoading = false) }
                }
                .collect { agent ->
                    if (agent != null) {
                        val messageCount = messageRepository.getMessageCount(agentId)
                        _uiState.update {
                            it.copy(
                                agent = agent,
                                messageCount = messageCount,
                                isLoading = false
                            )
                        }
                    } else {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }
        }
    }

    fun clearSession() {
        viewModelScope.launch {
            _uiState.update { it.copy(isClearingSession = true) }
            try {
                // Clear session info from agent
                agentRepository.clearSessionInfo(agentId)
                // Clear all messages
                messageRepository.deleteAllMessagesForAgent(agentId)
                // Disconnect the agent
                agentRepository.disconnectFromAgent(agentId)
                
                _uiState.update { 
                    it.copy(
                        isClearingSession = false,
                        sessionCleared = true,
                        messageCount = 0
                    )
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isClearingSession = false,
                        error = e.message
                    )
                }
            }
        }
    }

    fun deleteAgent() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeletingAgent = true) }
            try {
                agentRepository.disconnectFromAgent(agentId)
                messageRepository.deleteAllMessagesForAgent(agentId)
                agentRepository.deleteAgent(agentId)
                
                _uiState.update { 
                    it.copy(
                        isDeletingAgent = false,
                        agentDeleted = true
                    )
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isDeletingAgent = false,
                        error = e.message
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun resetSessionCleared() {
        _uiState.update { it.copy(sessionCleared = false) }
    }
}
