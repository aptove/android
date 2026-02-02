package com.acp.chat.ui.qr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.acp.chat.R
import com.acp.chat.service.PairingType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRScannerScreen(
    onNavigateBack: () -> Unit,
    onAgentConnected: (String) -> Unit,
    viewModel: QRScannerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    
    // Track if camera permission is granted
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    // Track if we're in scanning mode (showing camera)
    var isScanning by remember { mutableStateOf(false) }

    // Camera permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted) {
            isScanning = true
        }
    }

    // Function to launch scanner
    fun launchScanner() {
        if (hasCameraPermission) {
            isScanning = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(state) {
        when (val currentState = state) {
            is QRScannerState.Success -> {
                onAgentConnected(currentState.agent.id)
            }
            else -> {}
        }
    }

    val showManualEntry by viewModel.showManualEntry.collectAsState()
    val showPairingEntry by viewModel.showPairingEntry.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qr_scanner_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isScanning) {
                            isScanning = false
                        } else {
                            onNavigateBack()
                        }
                    }) {
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
            // Show camera scanner when in scanning mode
            if (isScanning && hasCameraPermission) {
                MLKitQRScanner(
                    modifier = Modifier.fillMaxSize(),
                    onQRCodeScanned = { qrContent ->
                        isScanning = false
                        viewModel.onQRCodeScanned(qrContent)
                    }
                )
            } else {
                // Show state-based UI when not scanning
                when (val currentState = state) {
                    is QRScannerState.Scanning -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(stringResource(R.string.qr_scanner_instructions))
                        
                        // Scan QR Code button
                        Button(
                            onClick = { launchScanner() },
                            modifier = Modifier.fillMaxWidth(0.8f)
                        ) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scan QR Code")
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Enter pairing code button
                        OutlinedButton(
                            onClick = { viewModel.showPairingEntry() },
                            modifier = Modifier.fillMaxWidth(0.8f)
                        ) {
                            Icon(
                                Icons.Default.Key,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Enter Pairing Code")
                        }
                        
                        // Manual entry button (legacy)
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
                            Text("Manual Connection")
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
                
                is QRScannerState.Pairing -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(currentState.status)
                    }
                }
                
                is QRScannerState.Error -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(16.dp)
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
        
        // Manual entry dialog (legacy)
        if (showManualEntry) {
            ManualEntryDialog(
                onDismiss = { viewModel.hideManualEntry() },
                onConnect = { url, clientId, clientSecret ->
                    viewModel.connectManually(url, clientId, clientSecret)
                }
            )
        }
        
        // Pairing code entry dialog
        if (showPairingEntry) {
            PairingCodeDialog(
                onDismiss = { viewModel.hidePairingEntry() },
                onConnect = { address, port, code, fingerprint, pairingType ->
                    viewModel.connectWithPairingCode(address, port, code, fingerprint, pairingType)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingCodeDialog(
    onDismiss: () -> Unit,
    onConnect: (String, String, String, String, PairingType) -> Unit
) {
    // For Android emulator, 10.0.2.2 points to host machine
    var address by remember { mutableStateOf("10.0.2.2") }
    var port by remember { mutableStateOf("8443") }
    var code by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var selectedPairingType by remember { mutableStateOf(PairingType.LOCAL) }
    var pairingTypeExpanded by remember { mutableStateOf(false) }
    
    val isValid = address.isNotBlank() && code.length == 6
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter Pairing Code") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Enter the pairing details from the bridge terminal",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                // Pairing Type dropdown
                ExposedDropdownMenuBox(
                    expanded = pairingTypeExpanded,
                    onExpandedChange = { pairingTypeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = when (selectedPairingType) {
                            PairingType.LOCAL -> "Local Network"
                            PairingType.CLOUDFLARE -> "Cloudflare"
                            PairingType.UNKNOWN -> "Unknown"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Connection Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pairingTypeExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = pairingTypeExpanded,
                        onDismissRequest = { pairingTypeExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Local Network") },
                            onClick = {
                                selectedPairingType = PairingType.LOCAL
                                pairingTypeExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Cloudflare (future)") },
                            onClick = {
                                selectedPairingType = PairingType.CLOUDFLARE
                                pairingTypeExpanded = false
                            },
                            enabled = false // Not yet implemented
                        )
                    }
                }
                
                // Address and Port
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Bridge Address") },
                        placeholder = { Text("10.0.2.2") },
                        modifier = Modifier.weight(2f),
                        singleLine = true
                    )
                    
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        placeholder = { Text("8443") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                
                // Pairing Code
                OutlinedTextField(
                    value = code,
                    onValueChange = { 
                        // Only allow digits, max 6 characters
                        if (it.length <= 6 && it.all { c -> c.isDigit() }) {
                            code = it 
                        }
                    },
                    label = { Text("Pairing Code") },
                    placeholder = { Text("123456") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = {
                        Text("6-digit code shown in the bridge terminal")
                    }
                )
                
                // Certificate Fingerprint (optional for manual entry)
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    label = { Text("Certificate Fingerprint (optional)") },
                    placeholder = { Text("SHA256:XX:XX:XX:...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2,
                    supportingText = {
                        Text("Optional: Validates the bridge's TLS certificate")
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConnect(address, port, code, fingerprint, selectedPairingType)
                },
                enabled = isValid
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
