package com.acp.chat.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
    viewModel: AgentListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

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
                    items(uiState.agents) { agent ->
                        AgentItem(
                            agent = agent,
                            onClick = { onNavigateToChat(agent.id) }
                        )
                    }
                }
            }
        }
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

@Composable
private fun AgentItem(
    agent: Agent,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
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
        }
    }
}
