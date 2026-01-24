package com.acp.chat.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ConnectionConfig(
    val url: String,
    val clientId: String,
    val clientSecret: String,
    val protocol: String = "acp",
    val version: String = "1.0"
) {
    fun toWebSocketUrl(): String {
        return url.replace("https://", "wss://").replace("http://", "ws://")
    }
}
