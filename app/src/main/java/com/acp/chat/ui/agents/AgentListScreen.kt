package com.acp.chat.ui.agents

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
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
import com.acp.chat.data.model.Agent
import com.acp.chat.data.model.ConnectionStatus
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentListScreen(
    onNavigateToChat: (String) -> Unit,
    onNavigateToQRScanner: () -> Unit,
    onNavigateToAgentConfig: (String) -> Unit,
    viewModel: AgentListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var agentToDelete by remember { mutableStateOf<Agent?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agents_title)) }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToQRScanner) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_agent))
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (uiState.agents.isEmpty()) {
                EmptyAgentsView(
                    onAddAgent = onNavigateToQRScanner,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.agents, key = { it.agentId }) { agent ->
                        AgentItem(
                            agent = agent,
                            onClick = { onNavigateToChat(agent.agentId) },
                            onConfigure = { onNavigateToAgentConfig(agent.agentId) },
                            onDelete = { agentToDelete = agent }
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    agentToDelete?.let { agent ->
        AlertDialog(
            onDismissRequest = { agentToDelete = null },
            title = { Text("Delete Agent?") },
            text = { Text("Are you sure you want to delete \"${agent.name}\"? This will remove all conversation history.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.disconnectAgent(agent.agentId)
                        agentToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { agentToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Error dialog
    uiState.error?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("Error") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
private fun EmptyAgentsView(
    onAddAgent: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.no_agents),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = stringResource(R.string.scan_qr_to_add),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Button(onClick = onAddAgent) {
            Text(stringResource(R.string.add_agent))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentItem(
    agent: Agent,
    onClick: () -> Unit,
    onConfigure: () -> Unit,
    onDelete: () -> Unit
) {
    var showDropdownMenu by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showDropdownMenu = true }
            )
    ) {
        Box {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.hsv(agent.colorHue.mod(360f), 0.5f, 0.8f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = agent.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                }

                // Agent info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agent.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = when (agent.connectionStatus) {
                            ConnectionStatus.CONNECTED -> stringResource(R.string.agent_connected)
                            ConnectionStatus.DISCONNECTED -> stringResource(R.string.agent_disconnected)
                            ConnectionStatus.RECONNECTING -> stringResource(R.string.agent_reconnecting)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (agent.connectionStatus) {
                            ConnectionStatus.CONNECTED -> Color(0xFF4CAF50)
                            ConnectionStatus.DISCONNECTED -> Color(0xFF9E9E9E)
                            ConnectionStatus.RECONNECTING -> Color(0xFFFFA726)
                        }
                    )
                }

                // Settings button
                IconButton(onClick = onConfigure) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.configure_agent),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            
            // Long-press dropdown menu
            DropdownMenu(
                expanded = showDropdownMenu,
                onDismissRequest = { showDropdownMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.configure_agent)) },
                    onClick = {
                        showDropdownMenu = false
                        onConfigure()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Settings, contentDescription = null)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    onClick = {
                        showDropdownMenu = false
                        onDelete()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                )
            }
        }
    }
}
