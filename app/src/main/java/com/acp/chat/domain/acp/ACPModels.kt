package com.acp.chat.domain.acp

// Note: Using SDK's AgentInfo directly now, removed wrapper
// import com.agentclientprotocol.agent.AgentInfo

sealed class ACPConnectionState {
    data object Disconnected : ACPConnectionState()
    data object Connecting : ACPConnectionState()
    data class Connected(val agentInfo: com.agentclientprotocol.agent.AgentInfo, val sessionId: String) : ACPConnectionState()
    data object Reconnecting : ACPConnectionState()
    data class Error(val message: String, val cause: Throwable? = null) : ACPConnectionState()
}

sealed class ACPMessage {
    data class TextChunk(val text: String, val isComplete: Boolean = false) : ACPMessage()
    data class Error(val message: String) : ACPMessage()
    data object Complete : ACPMessage()
}
