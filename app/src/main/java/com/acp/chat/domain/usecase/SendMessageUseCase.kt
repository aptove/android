package com.acp.chat.domain.usecase

import com.acp.chat.data.model.Message
import com.acp.chat.data.repository.MessageRepository
import com.agentclientprotocol.client.ClientSession
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class SendMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke(
        agentId: String,
        session: ClientSession,
        text: String
    ): Result<Flow<Message>> {
        if (text.isBlank()) {
            return Result.failure(Exception("Message cannot be empty"))
        }

        if (text.length > 10000) {
            return Result.failure(Exception("Message too long (max 10,000 characters)"))
        }

        return messageRepository.sendMessage(agentId, session, text)
    }
}
