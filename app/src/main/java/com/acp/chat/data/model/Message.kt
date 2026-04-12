package com.acp.chat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject
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
    TOOL_APPROVAL_REQUEST,
    THOUGHT,
    TOOL_STATUS
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
    val toolApproved: Boolean? = null,
    /** JSON-encoded list of [PermissionOptionInfo]. Persists approval options across restarts. */
    val toolOptions: String? = null
) {
    val toolApproval: ToolApprovalInfo?
        get() {
            if (type != MessageType.TOOL_APPROVAL_REQUEST || toolCallId == null || toolTitle == null) return null
            val options = toolOptions?.let { deserializeOptions(it) } ?: emptyList()
            return ToolApprovalInfo(toolCallId, toolTitle, toolCommand, toolApproved, options)
        }

    companion object {
        fun serializeOptions(options: List<PermissionOptionInfo>): String {
            return JSONArray().apply {
                options.forEach { opt ->
                    put(JSONObject().apply {
                        put("optionId", opt.optionId)
                        put("name", opt.name)
                        put("kind", opt.kind)
                    })
                }
            }.toString()
        }

        fun deserializeOptions(json: String): List<PermissionOptionInfo> {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    PermissionOptionInfo(
                        optionId = obj.getString("optionId"),
                        name = obj.getString("name"),
                        kind = obj.getString("kind")
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
