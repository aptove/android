package com.acp.chat.data.repository

import com.acp.chat.data.local.MessageDao
import com.acp.chat.data.model.Message
import com.acp.chat.data.model.MessageSender
import com.acp.chat.data.model.MessageStatus
import com.acp.chat.data.model.MessageType
import com.acp.chat.domain.acp.ACPClient
import com.acp.chat.domain.acp.ACPMessage
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.model.ContentBlock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.transform
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val acpClient: ACPClient
) {

    fun getMessagesForAgent(agentId: String): Flow<List<Message>> {
        return messageDao.getMessagesForAgent(agentId)
    }

    suspend fun getMessagesPaginated(agentId: String, limit: Int, offset: Int): List<Message> {
        return messageDao.getMessagesPaginated(agentId, limit, offset)
    }

    suspend fun sendMessage(
        agentId: String,
        session: ClientSession,
        text: String,
        images: List<ContentBlock.Image> = emptyList(),
        userMessageId: String = UUID.randomUUID().toString()
    ): Result<Flow<Message>> {
        // Build content blocks: images first, then text
        val contentBlocks = mutableListOf<ContentBlock>()
        contentBlocks.addAll(images)
        if (text.isNotBlank()) contentBlocks.add(ContentBlock.Text(text))

        // Use a placeholder text if images-only message
        val displayText = if (text.isBlank() && images.isNotEmpty()) "📎 Image" else text

        // Create user message
        val userMessage = Message(
            id = userMessageId,
            agentId = agentId,
            text = displayText,
            sender = MessageSender.USER,
            status = MessageStatus.SENDING
        )

        messageDao.insertMessage(userMessage)

        return try {
            // Mark user message as sent
            messageDao.updateMessageStatus(userMessage.id, MessageStatus.SENT)

            // Create agent message placeholder
            val agentMessageId = UUID.randomUUID().toString()
            val agentMessage = Message(
                id = agentMessageId,
                agentId = agentId,
                text = "",
                sender = MessageSender.AGENT,
                status = MessageStatus.SENDING
            )
            messageDao.insertMessage(agentMessage)

            // Track thought and tool-status message IDs across stream events
            var currentThoughtId: String? = null
            var currentToolId: String? = null
            // Tracks the current agent text bubble; advances when an intervening message is inserted
            var currentAgentMessageId = agentMessageId
            var needsNewBubble = false

            // Send via ACP and collect streaming response
            val messageFlow = acpClient.sendMessage(session, contentBlocks)
                .transform { acpMessage ->
                    when (acpMessage) {
                        is ACPMessage.TextChunk -> {
                            // If a tool/approval message was inserted since the last chunk,
                            // seal the current bubble and start a new one.
                            if (needsNewBubble) {
                                val prev = messageDao.getMessageById(currentAgentMessageId)
                                if (prev != null && prev.text.isNotEmpty()) {
                                    val sealed = prev.copy(status = MessageStatus.SENT)
                                    messageDao.updateMessage(sealed)
                                    emit(sealed)
                                    val newAgentMsg = Message(
                                        agentId = agentId,
                                        text = "",
                                        sender = MessageSender.AGENT,
                                        status = MessageStatus.SENDING
                                    )
                                    messageDao.insertMessage(newAgentMsg)
                                    currentAgentMessageId = newAgentMsg.id
                                }
                                needsNewBubble = false
                            }
                            val current = messageDao.getMessageById(currentAgentMessageId)
                            if (current != null) {
                                val updated = current.copy(
                                    text = current.text + acpMessage.text,
                                    status = if (acpMessage.isComplete) MessageStatus.DELIVERED else MessageStatus.SENDING
                                )
                                messageDao.updateMessage(updated)
                                emit(updated)
                            }
                        }
                        is ACPMessage.Thought -> {
                            if (currentThoughtId != null) {
                                messageDao.getMessageById(currentThoughtId!!)?.let { t ->
                                    val updated = t.copy(text = acpMessage.text)
                                    messageDao.updateMessage(updated)
                                    emit(updated)
                                }
                            } else {
                                val thoughtMsg = Message(
                                    agentId = agentId,
                                    text = acpMessage.text,
                                    sender = MessageSender.AGENT,
                                    status = MessageStatus.SENDING,
                                    type = MessageType.THOUGHT
                                )
                                messageDao.insertMessage(thoughtMsg)
                                currentThoughtId = thoughtMsg.id
                                emit(thoughtMsg)
                            }
                        }
                        is ACPMessage.ToolStatus -> {
                            val toolMsg = Message(
                                agentId = agentId,
                                text = acpMessage.text,
                                sender = MessageSender.AGENT,
                                status = MessageStatus.SENT,
                                type = MessageType.TOOL_STATUS
                            )
                            messageDao.insertMessage(toolMsg)
                            currentToolId = toolMsg.id
                            needsNewBubble = true
                            emit(toolMsg)
                        }
                        is ACPMessage.ToolStatusUpdate -> {
                            if (currentToolId != null) {
                                messageDao.getMessageById(currentToolId!!)?.let { t ->
                                    val updated = t.copy(text = t.text + "\n" + acpMessage.text)
                                    messageDao.updateMessage(updated)
                                    emit(updated)
                                }
                            } else {
                                // No current tool context — create a new one
                                val toolMsg = Message(
                                    agentId = agentId,
                                    text = acpMessage.text,
                                    sender = MessageSender.AGENT,
                                    status = MessageStatus.SENT,
                                    type = MessageType.TOOL_STATUS
                                )
                                messageDao.insertMessage(toolMsg)
                                currentToolId = toolMsg.id
                                emit(toolMsg)
                            }
                        }
                        is ACPMessage.Complete -> {
                            val current = messageDao.getMessageById(currentAgentMessageId)
                            if (current != null) {
                                val updated = current.copy(status = MessageStatus.DELIVERED)
                                messageDao.updateMessage(updated)
                                emit(updated)
                            }
                            // Stop thought spinner
                            currentThoughtId?.let { tId ->
                                messageDao.getMessageById(tId)?.let { t ->
                                    val updated = t.copy(status = MessageStatus.SENT)
                                    messageDao.updateMessage(updated)
                                    emit(updated)
                                }
                            }
                            currentToolId = null
                        }
                        is ACPMessage.Error -> {
                            val current = messageDao.getMessageById(currentAgentMessageId)
                            if (current != null) {
                                val updated = current.copy(
                                    status = MessageStatus.ERROR,
                                    error = acpMessage.message
                                )
                                messageDao.updateMessage(updated)
                                emit(updated)
                            }
                        }
                    }
                }
                .catch { e ->
                    messageDao.updateMessageStatus(currentAgentMessageId, MessageStatus.ERROR)
                }

            Result.success(messageFlow)
        } catch (e: Exception) {
            messageDao.updateMessageStatus(userMessage.id, MessageStatus.ERROR)
            Result.failure(e)
        }
    }

    suspend fun insertMessage(message: Message) {
        messageDao.insertMessage(message)
    }

    suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        messageDao.updateMessageStatus(messageId, status)
    }

    suspend fun appendToMessage(messageId: String, text: String) {
        val message = messageDao.getMessageById(messageId)
        if (message != null) {
            val updatedMessage = message.copy(text = message.text + text)
            messageDao.updateMessage(updatedMessage)
        }
    }

    suspend fun deleteAllMessagesForAgent(agentId: String) {
        messageDao.deleteAllMessagesForAgent(agentId)
    }

    suspend fun getMessageCount(agentId: String): Int {
        return messageDao.getMessageCount(agentId)
    }
}
