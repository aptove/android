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

enum class MessageType {
    TEXT,
    TOOL_APPROVAL_REQUEST
}

data class PermissionOptionInfo(
    val optionId: String,
    val name: String,
    val kind: String
)

data class ToolApprovalInfo(
    val toolCallId: String,
    val title: String,
    val command: String? = null,
    val approved: Boolean? = null,  // null=pending, true=approved, false=rejected
    val options: List<PermissionOptionInfo> = emptyList()
)

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val agentId: String,
    val text: String,
    val sender: MessageSender,
    val status: MessageStatus = MessageStatus.SENT,
    val timestamp: Long = System.currentTimeMillis(),
    val error: String? = null,
    val type: MessageType = MessageType.TEXT,
    val toolCallId: String? = null,
    val toolTitle: String? = null,
    val toolCommand: String? = null,
    val toolApproved: Boolean? = null
) {
    val toolApproval: ToolApprovalInfo?
        get() = if (type == MessageType.TOOL_APPROVAL_REQUEST && toolCallId != null && toolTitle != null) {
            ToolApprovalInfo(toolCallId, toolTitle, toolCommand, toolApproved)
        } else null
}
