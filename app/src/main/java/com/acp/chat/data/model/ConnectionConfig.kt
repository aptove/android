package com.acp.chat.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ConnectionConfig(
    val url: String,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val authToken: String? = null,
    val certFingerprint: String? = null,
    val protocol: String = "acp",
    val version: String = "1.0"
) {
    fun toWebSocketUrl(): String {
        return url.replace("https://", "wss://").replace("http://", "ws://")
    }
    
    /** Whether this is a secure TLS connection */
    val isSecure: Boolean
        get() = url.startsWith("wss://") || url.startsWith("https://")
    
    /** Whether this connection uses a self-signed certificate (has fingerprint) */
    val hasSelfSignedCert: Boolean
        get() = !certFingerprint.isNullOrBlank()
}
