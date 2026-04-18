package com.acp.chat.ui.qr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
                onAgentConnected(currentState.agent.agentId)
            }
            else -> {}
        }
    }

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
                            Text(stringResource(R.string.qr_scanner_title))
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
                            Text(stringResource(R.string.qr_enter_pairing_code))
                        }
                    }
                }
                
                is QRScannerState.Processing -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.qr_connecting))
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
                            Text(stringResource(R.string.qr_try_again))
                        }
                    }
                }
                
                is QRScannerState.Success -> {
                    // Will navigate away via LaunchedEffect
                }
                }
            }
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
        title = { Text(stringResource(R.string.qr_enter_pairing_code)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.qr_pairing_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                
                // Pairing Type dropdown
                ExposedDropdownMenuBox(
                    expanded = pairingTypeExpanded,
                    onExpandedChange = { pairingTypeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = when (selectedPairingType) {
                            PairingType.LOCAL -> stringResource(R.string.transport_local_network)
                            PairingType.CLOUDFLARE -> stringResource(R.string.transport_cloudflare)
                            PairingType.TAILSCALE -> stringResource(R.string.transport_tailscale)
                            PairingType.UNKNOWN -> "Unknown"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.qr_connection_type)) },
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
                            text = { Text(stringResource(R.string.transport_local_network)) },
                            onClick = {
                                selectedPairingType = PairingType.LOCAL
                                pairingTypeExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.transport_cloudflare)) },
                            onClick = {
                                selectedPairingType = PairingType.CLOUDFLARE
                                pairingTypeExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.transport_tailscale)) },
                            onClick = {
                                selectedPairingType = PairingType.TAILSCALE
                                pairingTypeExpanded = false
                            }
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
                        label = { Text(stringResource(R.string.qr_bridge_address)) },
                        placeholder = { Text("10.0.2.2") },
                        modifier = Modifier.weight(2f),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text(stringResource(R.string.qr_port)) },
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
                    label = { Text(stringResource(R.string.qr_pairing_code_label)) },
                    placeholder = { Text("123456") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = {
                        Text(stringResource(R.string.qr_pairing_code_hint))
                    }
                )
                
                // Certificate Fingerprint (optional for manual entry)
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    label = { Text(stringResource(R.string.qr_certificate_fingerprint)) },
                    placeholder = { Text("SHA256:XX:XX:XX:...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2,
                    supportingText = {
                        Text(stringResource(R.string.qr_fingerprint_hint))
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
                Text(stringResource(R.string.connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
