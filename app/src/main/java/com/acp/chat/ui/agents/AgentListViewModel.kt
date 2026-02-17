package com.acp.chat.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acp.chat.data.model.Agent
import com.acp.chat.data.repository.MessageRepository
import com.acp.chat.domain.AgentManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AgentListUiState(
    val agents: List<Agent> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AgentListViewModel @Inject constructor(
    private val agentManager: AgentManager,
    private val messageRepository: MessageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentListUiState())
    val uiState: StateFlow<AgentListUiState> = _uiState.asStateFlow()

    init {
        loadAgents()
    }

    private fun loadAgents() {
        viewModelScope.launch {
            agentManager.observeAgents()
                .catch { e ->
                    _uiState.update { it.copy(error = e.message, isLoading = false) }
                }
                .collect { agents ->
                    _uiState.update { it.copy(agents = agents, isLoading = false) }
                }
        }
    }

    fun disconnectAgent(agentId: String) {
        viewModelScope.launch {
            try {
                agentManager.disconnectFromAgent(agentId)
                messageRepository.deleteAllMessagesForAgent(agentId)
                agentManager.deleteAgent(agentId)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
