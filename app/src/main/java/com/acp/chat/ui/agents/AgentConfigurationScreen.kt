package com.acp.chat.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.acp.chat.R
import com.acp.chat.data.model.ConnectionStatus
import com.acp.chat.data.model.TransportEndpoint
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentConfigurationScreen(
    onNavigateBack: () -> Unit,
    viewModel: AgentConfigurationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showClearSessionDialog by remember { mutableStateOf(false) }
    var showDeleteAgentDialog by remember { mutableStateOf(false) }

    // Navigate back when agent is deleted
    LaunchedEffect(uiState.agentDeleted) {
        if (uiState.agentDeleted) {
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_configuration_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (uiState.agent == null) {
                Text(
                    text = stringResource(R.string.agent_not_found),
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                val agent = uiState.agent!!
                
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Agent Info Section
                    AgentInfoCard(agent = agent)

                    // Session Info Section
                    SessionInfoCard(
                        agent = agent,
                        messageCount = uiState.messageCount
                    )

                    // Transports Section (only shown when transport endpoints exist)
                    if (uiState.endpoints.isNotEmpty()) {
                        TransportsCard(
                            endpoints = uiState.endpoints,
                            preferredTransport = agent.preferredTransport,
                            onSetPreferred = { viewModel.setPreferredTransport(it) },
                            onDelete = { viewModel.deleteEndpoint(it) }
                        )
                    }

                    // Actions Section
                    ActionsCard(
                        isClearingSession = uiState.isClearingSession,
                        isDeletingAgent = uiState.isDeletingAgent,
                        hasSession = agent.activeSessionId != null,
                        onClearSession = { showClearSessionDialog = true },
                        onDeleteAgent = { showDeleteAgentDialog = true }
                    )
                }
            }
        }
    }

    // Clear Session Confirmation Dialog
    if (showClearSessionDialog) {
        AlertDialog(
            onDismissRequest = { showClearSessionDialog = false },
            title = { Text(stringResource(R.string.clear_session_title)) },
            text = { Text(stringResource(R.string.clear_session_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearSession()
                        showClearSessionDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearSessionDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Delete Agent Confirmation Dialog
    if (showDeleteAgentDialog) {
        val agent = uiState.agent
        AlertDialog(
            onDismissRequest = { showDeleteAgentDialog = false },
            title = { Text(stringResource(R.string.delete_agent_title)) },
            text = { 
                Text(stringResource(R.string.delete_agent_message, agent?.name ?: "")) 
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAgent()
                        showDeleteAgentDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAgentDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Session cleared snackbar
    if (uiState.sessionCleared) {
        LaunchedEffect(Unit) {
            // Let the UI show the message briefly, then reset
            kotlinx.coroutines.delay(2000)
            viewModel.resetSessionCleared()
        }
    }

    // Error dialog
    uiState.error?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text(stringResource(R.string.error)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}

@Composable
private fun AgentInfoCard(agent: com.acp.chat.data.model.Agent) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.agent_info_title),
                style = MaterialTheme.typography.titleMedium
            )
            
            InfoRow(
                label = stringResource(R.string.name_label),
                value = agent.name
            )
            
            InfoRow(
                label = stringResource(R.string.url_label),
                value = agent.url
            )
            
            InfoRow(
                label = stringResource(R.string.status_label),
                value = when (agent.connectionStatus) {
                    ConnectionStatus.CONNECTED -> stringResource(R.string.agent_connected)
                    ConnectionStatus.DISCONNECTED -> stringResource(R.string.agent_disconnected)
                    ConnectionStatus.RECONNECTING -> stringResource(R.string.agent_reconnecting)
                }
            )
            
            InfoRow(
                label = stringResource(R.string.protocol_version_label),
                value = agent.protocolVersion
            )
            
            agent.lastConnectedAt?.let { timestamp ->
                InfoRow(
                    label = stringResource(R.string.last_connected_label),
                    value = formatTimestamp(timestamp)
                )
            }
        }
    }
}

@Composable
private fun SessionInfoCard(
    agent: com.acp.chat.data.model.Agent,
    messageCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.session_info_title),
                style = MaterialTheme.typography.titleMedium
            )
            
            if (agent.activeSessionId != null) {
                InfoRow(
                    label = stringResource(R.string.session_id_label),
                    value = agent.activeSessionId.take(16) + "..."
                )
                
                agent.sessionStartedAt?.let { timestamp ->
                    InfoRow(
                        label = stringResource(R.string.session_started_label),
                        value = formatTimestamp(timestamp)
                    )
                }
                
                InfoRow(
                    label = stringResource(R.string.message_count_label),
                    value = messageCount.toString()
                )
                
                InfoRow(
                    label = stringResource(R.string.supports_resume_label),
                    value = if (agent.supportsLoadSession) 
                        stringResource(R.string.yes) 
                    else 
                        stringResource(R.string.no)
                )
            } else {
                Text(
                    text = stringResource(R.string.no_active_session),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun TransportsCard(
    endpoints: List<TransportEndpoint>,
    preferredTransport: String?,
    onSetPreferred: (String?) -> Unit,
    onDelete: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.transports_title),
                style = MaterialTheme.typography.titleMedium
            )
            endpoints.forEach { endpoint ->
                TransportEndpointRow(
                    endpoint = endpoint,
                    isPreferred = endpoint.transport == preferredTransport,
                    onSetPreferred = {
                        onSetPreferred(if (endpoint.transport == preferredTransport) null else endpoint.transport)
                    },
                    onDelete = { onDelete(endpoint.endpointId) }
                )
            }
        }
    }
}

@Composable
private fun TransportEndpointRow(
    endpoint: TransportEndpoint,
    isPreferred: Boolean,
    onSetPreferred: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Active indicator
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (endpoint.isActive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = transportDisplayName(endpoint.transport),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = endpoint.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1
            )
        }

        IconButton(onClick = onSetPreferred) {
            Icon(
                imageVector = if (isPreferred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = stringResource(R.string.transport_preferred),
                tint = if (isPreferred) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }

        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.transport_delete),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

private fun transportDisplayName(transport: String): String = when (transport) {
    "tailscale-serve" -> "Tailscale (Serve)"
    "tailscale-ip"    -> "Tailscale (IP)"
    "cloudflare"      -> "Cloudflare"
    "local"           -> "Local Network"
    else              -> transport
}

@Composable
private fun ActionsCard(
    isClearingSession: Boolean,
    isDeletingAgent: Boolean,
    hasSession: Boolean,
    onClearSession: () -> Unit,
    onDeleteAgent: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.actions_title),
                style = MaterialTheme.typography.titleMedium
            )
            
            // Clear Session Button
            OutlinedButton(
                onClick = onClearSession,
                enabled = !isClearingSession && hasSession,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isClearingSession) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.clear_session_button))
            }
            
            Text(
                text = stringResource(R.string.clear_session_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            
            // Delete Agent Button
            Button(
                onClick = onDeleteAgent,
                enabled = !isDeletingAgent,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                if (isDeletingAgent) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.delete_agent_button))
            }
            
            Text(
                text = stringResource(R.string.delete_agent_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val formatter = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
    return formatter.format(Date(timestamp))
}
