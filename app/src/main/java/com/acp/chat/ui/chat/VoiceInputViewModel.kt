package com.acp.chat.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class RecordingState {
    data object Idle : RecordingState()
    data object RequestingPermission : RecordingState()
    data object Recording : RecordingState()
    data object Processing : RecordingState()
    data class Error(val message: String) : RecordingState()
}

@HiltViewModel
class VoiceInputViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _rawTranscript = MutableStateFlow<String?>(null)
    val rawTranscript: StateFlow<String?> = _rawTranscript.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null

    fun startRecording() {
        _recordingState.value = RecordingState.RequestingPermission
        viewModelScope.launch(Dispatchers.Main) {
            createAndStartRecognizer()
        }
    }

    private fun createAndStartRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _recordingState.value = RecordingState.Error("Speech recognition not available on this device")
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _recordingState.value = RecordingState.Recording
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    _recordingState.value = RecordingState.Processing
                }

                override fun onError(error: Int) {
                    val message = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                        else -> "Recognition error ($error)"
                    }
                    _recordingState.value = RecordingState.Error(message)
                    _rawTranscript.value = null
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val transcript = matches?.firstOrNull()
                    if (transcript != null) {
                        _rawTranscript.value = transcript
                    } else {
                        _recordingState.value = RecordingState.Error("No speech recognized")
                    }
                    _recordingState.value = RecordingState.Idle
                }

                override fun onPartialResults(partialResults: Bundle?) {}

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            recognizer.startListening(intent)
        }
    }

    fun stopRecording() {
        if (_recordingState.value is RecordingState.Recording) {
            _recordingState.value = RecordingState.Processing
            viewModelScope.launch(Dispatchers.Main) {
                speechRecognizer?.stopListening()
            }
        }
    }

    fun clearTranscript() {
        _rawTranscript.value = null
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
