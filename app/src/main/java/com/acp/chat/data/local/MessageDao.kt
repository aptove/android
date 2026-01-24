package com.acp.chat.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.acp.chat.data.model.Message
import com.acp.chat.data.model.MessageStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE agentId = :agentId ORDER BY timestamp ASC")
    fun getMessagesForAgent(agentId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE agentId = :agentId ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getMessagesPaginated(agentId: String, limit: Int, offset: Int): List<Message>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): Message?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message)

    @Update
    suspend fun updateMessage(message: Message)

    @Delete
    suspend fun deleteMessage(message: Message)

    @Query("DELETE FROM messages WHERE agentId = :agentId")
    suspend fun deleteAllMessagesForAgent(agentId: String)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus)

    @Query("UPDATE messages SET text = :text WHERE id = :messageId")
    suspend fun appendMessageText(messageId: String, text: String)

    @Query("SELECT COUNT(*) FROM messages WHERE agentId = :agentId")
    suspend fun getMessageCount(agentId: String): Int
}
