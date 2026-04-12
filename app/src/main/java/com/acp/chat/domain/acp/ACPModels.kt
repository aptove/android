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
    data class Thought(val text: String) : ACPMessage()
    /** A new tool call started. Creates a new tool-status message bubble. */
    data class ToolStatus(val text: String) : ACPMessage()
    /** An update to the in-progress tool call. Appends to the current tool-status bubble. */
    data class ToolStatusUpdate(val text: String) : ACPMessage()
    data class Error(val message: String) : ACPMessage()
    data object Complete : ACPMessage()
}
