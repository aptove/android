package com.acp.chat.ui.qr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acp.chat.data.model.Agent
import com.acp.chat.domain.usecase.ConnectAgentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class QRScannerState {
    data object Scanning : QRScannerState()
    data object Processing : QRScannerState()
    data class Success(val agent: Agent) : QRScannerState()
    data class Error(val message: String) : QRScannerState()
}

@HiltViewModel
class QRScannerViewModel @Inject constructor(
    private val connectAgentUseCase: ConnectAgentUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<QRScannerState>(QRScannerState.Scanning)
    val state: StateFlow<QRScannerState> = _state.asStateFlow()
    
    private val _showManualEntry = MutableStateFlow(false)
    val showManualEntry: StateFlow<Boolean> = _showManualEntry.asStateFlow()

    fun onQRCodeScanned(qrData: String) {
        viewModelScope.launch {
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
            
            onQRCodeScanned(qrData)
        }
    }
}
