package com.acp.chat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class MessageSender {
    USER,
    AGENT
}

enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    ERROR,
    TIMEOUT
}

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val agentId: String,
    val text: String,
    val sender: MessageSender,
    val status: MessageStatus = MessageStatus.SENT,
    val timestamp: Long = System.currentTimeMillis(),
    val error: String? = null
)
