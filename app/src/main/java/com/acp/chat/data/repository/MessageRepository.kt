package com.acp.chat.data.repository

import com.acp.chat.data.local.MessageDao
import com.acp.chat.data.model.Message
import com.acp.chat.data.model.MessageSender
import com.acp.chat.data.model.MessageStatus
import com.acp.chat.domain.acp.ACPClient
import com.acp.chat.domain.acp.ACPMessage
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.model.ContentBlock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
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

            // Send via ACP and collect streaming response
            val messageFlow = acpClient.sendMessage(session, contentBlocks)
                .map { acpMessage ->
                    when (acpMessage) {
                        is ACPMessage.TextChunk -> {
                            // Append text to agent message
                            val current = messageDao.getMessageById(agentMessageId)
                            if (current != null) {
                                val updated = current.copy(
                                    text = current.text + acpMessage.text,
                                    status = if (acpMessage.isComplete) MessageStatus.DELIVERED else MessageStatus.SENDING
                                )
                                messageDao.updateMessage(updated)
                                updated
                            } else {
                                agentMessage
                            }
                        }
                        is ACPMessage.Complete -> {
                            val current = messageDao.getMessageById(agentMessageId)
                            if (current != null) {
                                val updated = current.copy(status = MessageStatus.DELIVERED)
                                messageDao.updateMessage(updated)
                                updated
                            } else {
                                agentMessage
                            }
                        }
                        is ACPMessage.Error -> {
                            val current = messageDao.getMessageById(agentMessageId)
                            if (current != null) {
                                val updated = current.copy(
                                    status = MessageStatus.ERROR,
                                    error = acpMessage.message
                                )
                                messageDao.updateMessage(updated)
                                updated
                            } else {
                                agentMessage
                            }
                        }
                    }
                }
                .catch { e ->
                    messageDao.updateMessageStatus(agentMessageId, MessageStatus.ERROR)
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
