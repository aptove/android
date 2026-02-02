package com.acp.chat.ui.qr

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acp.chat.data.model.Agent
import com.acp.chat.domain.usecase.ConnectAgentUseCase
import com.acp.chat.service.PairingResult
import com.acp.chat.service.PairingService
import com.acp.chat.service.PairingType
import com.acp.chat.service.PairingURL
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "QRScannerViewModel"

sealed class QRScannerState {
    data object Scanning : QRScannerState()
    data object Processing : QRScannerState()
    data class Pairing(val status: String) : QRScannerState()
    data class Success(val agent: Agent) : QRScannerState()
    data class Error(val message: String) : QRScannerState()
}

@HiltViewModel
class QRScannerViewModel @Inject constructor(
    private val connectAgentUseCase: ConnectAgentUseCase,
    private val pairingService: PairingService
) : ViewModel() {

    private val _state = MutableStateFlow<QRScannerState>(QRScannerState.Scanning)
    val state: StateFlow<QRScannerState> = _state.asStateFlow()
    
    private val _showManualEntry = MutableStateFlow(false)
    val showManualEntry: StateFlow<Boolean> = _showManualEntry.asStateFlow()
    
    private val _showPairingEntry = MutableStateFlow(false)
    val showPairingEntry: StateFlow<Boolean> = _showPairingEntry.asStateFlow()

    fun onQRCodeScanned(qrData: String) {
        viewModelScope.launch {
            Log.d(TAG, "QR code scanned: ${qrData.take(50)}...")
            
            // Check if this is a pairing URL (starts with https://)
            if (qrData.startsWith("https://")) {
                handlePairingURL(qrData)
            } else {
                // Legacy JSON format
                handleLegacyJSON(qrData)
            }
        }
    }
    
    /**
     * Handle new pairing URL format from bridge QR code.
     * Format: https://IP:PORT/pair/local?code=XXXXXX&fp=SHA256:...
     */
    private suspend fun handlePairingURL(urlString: String) {
        val pairingURL = PairingURL.parse(urlString)
        
        if (pairingURL == null) {
            _state.value = QRScannerState.Error("Invalid pairing URL format")
            return
        }
        
        Log.d(TAG, "Parsed pairing URL: type=${pairingURL.pairingType}, code=${pairingURL.code}")
        
        _state.value = QRScannerState.Pairing("Connecting to bridge...")
        
        when (val result = pairingService.pair(pairingURL)) {
            is PairingResult.Success -> {
                Log.d(TAG, "Pairing successful, connecting to WebSocket")
                _state.value = QRScannerState.Pairing("Pairing successful, connecting...")
                
                // Now connect using the connection config
                val connectResult = connectAgentUseCase.connectWithConfig(result.config)
                
                _state.value = if (connectResult.isSuccess) {
                    QRScannerState.Success(connectResult.getOrThrow())
                } else {
                    QRScannerState.Error(
                        connectResult.exceptionOrNull()?.message ?: "Failed to connect after pairing"
                    )
                }
            }
            
            is PairingResult.InvalidCode -> {
                Log.w(TAG, "Invalid pairing code: ${result.message}")
                _state.value = QRScannerState.Error("Invalid or expired pairing code. Please scan a new QR code.")
            }
            
            is PairingResult.RateLimited -> {
                Log.w(TAG, "Rate limited: ${result.message}")
                _state.value = QRScannerState.Error("Too many attempts. Please wait and try again.")
            }
            
            is PairingResult.CertificateMismatch -> {
                Log.e(TAG, "Certificate mismatch: expected=${result.expected}, actual=${result.actual}")
                _state.value = QRScannerState.Error(
                    "Security error: Certificate mismatch. Possible MITM attack. Do not proceed."
                )
            }
            
            is PairingResult.NetworkError -> {
                Log.e(TAG, "Network error: ${result.message}")
                _state.value = QRScannerState.Error("Network error: ${result.message}")
            }
            
            is PairingResult.UnknownError -> {
                Log.e(TAG, "Unknown error: ${result.message}")
                _state.value = QRScannerState.Error(result.message)
            }
        }
    }
    
    /**
     * Handle legacy JSON format for backwards compatibility.
     */
    private suspend fun handleLegacyJSON(qrData: String) {
        _state.value = QRScannerState.Processing

        val result = connectAgentUseCase(qrData)

        _state.value = if (result.isSuccess) {
            QRScannerState.Success(result.getOrThrow())
        } else {
            QRScannerState.Error(
                result.exceptionOrNull()?.message ?: "Failed to connect"
            )
        }
    }

    fun resetToScanning() {
        _state.value = QRScannerState.Scanning
    }
    
    fun showManualEntry() {
        _showManualEntry.value = true
    }
    
    fun hideManualEntry() {
        _showManualEntry.value = false
    }
    
    fun showPairingEntry() {
        _showPairingEntry.value = true
    }
    
    fun hidePairingEntry() {
        _showPairingEntry.value = false
    }
    
    /**
     * Connect using legacy manual entry (URL + credentials).
     */
    fun connectManually(url: String, clientId: String, clientSecret: String) {
        viewModelScope.launch {
            _state.value = QRScannerState.Processing
            _showManualEntry.value = false
            
            // For localhost, use dummy credentials if not provided
            val isLocalhost = url.contains("localhost") || url.contains("127.0.0.1") || url.contains("10.0.2.2")
            val finalClientId = if (clientId.isBlank() && isLocalhost) "local" else clientId
            val finalClientSecret = if (clientSecret.isBlank() && isLocalhost) "local" else clientSecret
            
            val qrData = """
                {
                    "url": "$url",
                    "clientId": "$finalClientId",
                    "clientSecret": "$finalClientSecret",
                    "protocol": "acp",
                    "version": "1.0"
                }
            """.trimIndent()
            
            handleLegacyJSON(qrData)
        }
    }
    
    /**
     * Connect using pairing code (for manual entry when QR scanning isn't possible).
     */
    fun connectWithPairingCode(
        address: String,
        port: String,
        code: String,
        fingerprint: String,
        pairingType: PairingType
    ) {
        viewModelScope.launch {
            _state.value = QRScannerState.Pairing("Connecting...")
            _showPairingEntry.value = false
            
            // Build pairing URL
            val pathSegment = when (pairingType) {
                PairingType.LOCAL -> "local"
                PairingType.CLOUDFLARE -> "cloudflare"
                PairingType.UNKNOWN -> "local" // Default to local
            }
            
            val portPart = if (port.isNotBlank()) ":$port" else ""
            val fpPart = if (fingerprint.isNotBlank()) "&fp=${java.net.URLEncoder.encode(fingerprint, "UTF-8")}" else ""
            val pairingURLString = "https://$address$portPart/pair/$pathSegment?code=$code$fpPart"
            
            Log.d(TAG, "Manual pairing URL: $pairingURLString")
            
            handlePairingURL(pairingURLString)
        }
    }
}
