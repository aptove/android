package com.acp.chat.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acp.chat.data.model.Agent
import com.acp.chat.data.model.ConnectionStatus
import com.acp.chat.data.repository.MessageRepository
import com.acp.chat.domain.AgentManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AgentListUiState(
    val agents: List<Agent> = emptyList(),
    /** Maps agentId → transport name of the currently active endpoint. */
    val activeTransports: Map<String, String> = emptyMap(),
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

    private var autoConnectTriggered = false

    init {
        loadAgents()
    }

    private fun loadAgents() {
        viewModelScope.launch {
            combine(
                agentManager.observeAgents(),
                agentManager.observeAllActiveEndpoints()
            ) { agents, activeEndpoints ->
                val activeMap = activeEndpoints.associate { it.agentId to it.transport }
                Pair(agents, activeMap)
            }
                .catch { e ->
                    _uiState.update { it.copy(error = e.message, isLoading = false) }
                }
                .collect { (agents, activeMap) ->
                    _uiState.update {
                        it.copy(agents = agents, activeTransports = activeMap, isLoading = false)
                    }

                    // Auto-connect all agents on first non-empty load (task 17.7)
                    if (!autoConnectTriggered && agents.isNotEmpty()) {
                        autoConnectTriggered = true
                        autoConnectAllAgents(agents)
                        startBackgroundRetry()
                    }
                }
        }
    }

    /** Connect all agents concurrently on app launch. */
    private fun autoConnectAllAgents(agents: List<Agent>) {
        for (agent in agents) {
            viewModelScope.launch {
                agentManager.connectAgent(agent.agentId)
            }
        }
    }

    /** Retry all disconnected agents every 30 seconds in the background (task 17.6). */
    private fun startBackgroundRetry() {
        viewModelScope.launch {
            while (true) {
                delay(30_000L)
                val disconnected = _uiState.value.agents.filter {
                    it.connectionStatus == ConnectionStatus.DISCONNECTED
                }
                for (agent in disconnected) {
                    launch {
                        agentManager.connectAgent(agent.agentId)
                    }
                }
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
