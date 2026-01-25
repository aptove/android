package com.acp.chat.ui.qr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.acp.chat.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRScannerScreen(
    onNavigateBack: () -> Unit,
    onAgentConnected: (String) -> Unit,
    viewModel: QRScannerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state) {
        when (val currentState = state) {
            is QRScannerState.Success -> {
                onAgentConnected(currentState.agent.id)
            }
            else -> {}
        }
    }

    val showManualEntry by viewModel.showManualEntry.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qr_scanner_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            if (state is QRScannerState.Scanning) {
                FloatingActionButton(
                    onClick = { viewModel.showManualEntry() }
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Manual Entry")
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when (val currentState = state) {
                is QRScannerState.Scanning -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(stringResource(R.string.qr_scanner_instructions))
                        
                        // Manual entry button
                        OutlinedButton(
                            onClick = { viewModel.showManualEntry() },
                            modifier = Modifier.fillMaxWidth(0.8f)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Enter URL Manually")
                        }
                        
                        // Placeholder - would integrate with CameraX + ZXing
                        Button(onClick = {
                            // For demo: simulate QR scan with sample data
                            val sampleQR = """
                                {
                                    "url": "https://agent.example.com",
                                    "clientId": "test.access",
                                    "clientSecret": "secret123",
                                    "protocol": "acp",
                                    "version": "1.0"
                                }
                            """.trimIndent()
                            viewModel.onQRCodeScanned(sampleQR)
                        }) {
                            Text("Simulate QR Scan (Demo)")
                        }
                    }
                }
                
                is QRScannerState.Processing -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("Connecting to agent...")
                    }
                }
                
                is QRScannerState.Error -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = currentState.message,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = { viewModel.resetToScanning() }) {
                            Text("Try Again")
                        }
                    }
                }
                
                is QRScannerState.Success -> {
                    // Will navigate away via LaunchedEffect
                }
            }
        }
        
        // Manual entry dialog
        if (showManualEntry) {
            ManualEntryDialog(
                onDismiss = { viewModel.hideManualEntry() },
                onConnect = { url, clientId, clientSecret ->
                    viewModel.connectManually(url, clientId, clientSecret)
                }
            )
        }
    }
}

@Composable
private fun ManualEntryDialog(
    onDismiss: () -> Unit,
    onConnect: (String, String, String) -> Unit
) {
    var url by remember { mutableStateOf("ws://10.0.2.2:8080") }
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual Connection") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Enter connection details manually",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("WebSocket URL") },
                    placeholder = { Text("ws://10.0.2.2:8080") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                Text(
                    text = "Optional for localhost connections:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text("Client ID (optional)") },
                    placeholder = { Text("test.access") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                OutlinedTextField(
                    value = clientSecret,
                    onValueChange = { clientSecret = it },
                    label = { Text("Client Secret (optional)") },
                    placeholder = { Text("secret123") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (url.isNotBlank()) {
                        onConnect(url, clientId, clientSecret)
                    }
                },
                enabled = url.isNotBlank()
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
