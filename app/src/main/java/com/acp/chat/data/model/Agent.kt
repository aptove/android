package com.acp.chat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class ConnectionStatus {
    CONNECTED,
    DISCONNECTED,
    RECONNECTING
}

@Entity(tableName = "agents")
data class Agent(
    @PrimaryKey
    val agentId: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String? = null,
    val url: String,
    val protocolVersion: String,
    val capabilities: String = "[]", // JSON string
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val lastConnectedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val colorHue: Float = (agentId.hashCode().toFloat().mod(360f)),
    // Session persistence fields
    val activeSessionId: String? = null,
    val sessionStartedAt: Long? = null,
    val supportsLoadSession: Boolean = false
)
