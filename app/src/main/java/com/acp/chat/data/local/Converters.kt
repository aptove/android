package com.acp.chat.data.local

import androidx.room.TypeConverter
import com.acp.chat.data.model.ConnectionStatus
import com.acp.chat.data.model.MessageSender
import com.acp.chat.data.model.MessageStatus
import com.acp.chat.data.model.MessageType

class Converters {
    @TypeConverter
    fun fromConnectionStatus(value: ConnectionStatus): String = value.name

    @TypeConverter
    fun toConnectionStatus(value: String): ConnectionStatus = ConnectionStatus.valueOf(value)

    @TypeConverter
    fun fromMessageSender(value: MessageSender): String = value.name

    @TypeConverter
    fun toMessageSender(value: String): MessageSender = MessageSender.valueOf(value)

    @TypeConverter
    fun fromMessageStatus(value: MessageStatus): String = value.name

    @TypeConverter
    fun toMessageStatus(value: String): MessageStatus = MessageStatus.valueOf(value)
    
    @TypeConverter
    fun fromMessageType(value: MessageType): String = value.name
    
    @TypeConverter
    fun toMessageType(value: String): MessageType = MessageType.valueOf(value)
}
