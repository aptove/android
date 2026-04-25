package com.acp.chat.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** Priority constants — lower value = higher priority in fallback order. */
object TransportPriority {
    const val TAILSCALE_SERVE = 0
    const val CLOUDFLARE      = 1
    const val LOCAL           = 2

    fun forTransport(transport: String): Int = when (transport) {
        "tailscale-serve" -> TAILSCALE_SERVE
        "cloudflare"      -> CLOUDFLARE
        else              -> LOCAL
    }
}

@Entity(
    tableName = "transport_endpoints",
    foreignKeys = [
        ForeignKey(
            entity = Agent::class,
            parentColumns = ["agentId"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("agentId")]
)
data class TransportEndpoint(
    @PrimaryKey
    val endpointId: String = UUID.randomUUID().toString(),
    val agentId: String,
    val transport: String,
    val url: String,
    val isActive: Boolean = false,
    val lastConnectedAt: Long? = null,
    val priority: Int = TransportPriority.LOCAL
)
