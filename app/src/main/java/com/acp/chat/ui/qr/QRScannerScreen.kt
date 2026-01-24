package com.acp.chat.ui.qr

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    }
}
