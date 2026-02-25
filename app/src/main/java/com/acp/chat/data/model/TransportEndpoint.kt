package com.acp.chat.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** Priority constants — lower value = higher priority in fallback order. */
object TransportPriority {
    const val TAILSCALE_SERVE = 0
    const val TAILSCALE_IP    = 1
    const val CLOUDFLARE      = 2
    const val LOCAL           = 3

    fun forTransport(transport: String): Int = when (transport) {
        "tailscale-serve" -> TAILSCALE_SERVE
        "tailscale-ip"    -> TAILSCALE_IP
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
