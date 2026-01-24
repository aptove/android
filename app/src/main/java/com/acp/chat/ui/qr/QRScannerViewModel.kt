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
}
