package com.acp.chat.ui.chat

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.acp.chat.R
import com.acp.chat.data.model.Message
import com.acp.chat.data.model.MessageSender
import com.acp.chat.data.model.MessageType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
    voiceInputViewModel: VoiceInputViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val voiceState by voiceInputViewModel.recordingState.collectAsState()
    val rawTranscript by voiceInputViewModel.rawTranscript.collectAsState()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceInputViewModel.startRecording()
        }
    }

    LaunchedEffect(rawTranscript) {
        rawTranscript?.let { transcript ->
            voiceInputViewModel.clearTranscript()
            viewModel.sendVoiceCorrectionRequest(transcript)
        }
    }

    LaunchedEffect(uiState.voiceCorrectedText) {
        uiState.voiceCorrectedText?.let { corrected ->
            viewModel.updateInputText(corrected)
            viewModel.clearVoiceCorrectedText()
        }
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(uiState.messages.size - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(uiState.agent?.name ?: stringResource(R.string.chat_title))
                        // Show session status indicator
                        if (uiState.showSessionIndicator) {
                            val statusText = when (uiState.sessionResumed) {
                                true -> stringResource(R.string.session_resumed)
                                false -> stringResource(R.string.session_new)
                                null -> ""
                            }
                            if (statusText.isNotEmpty()) {
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (uiState.sessionResumed == true)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            MessageInputBar(
                inputText = uiState.inputText,
                onInputTextChange = viewModel::updateInputText,
                onSendMessage = viewModel::sendMessage,
                onVoiceInput = {
                    if (voiceState is RecordingState.Recording) {
                        voiceInputViewModel.stopRecording()
                    } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                isRecording = voiceState is RecordingState.Recording,
                isCorrectionPending = uiState.isVoiceCorrectionPending,
                enabled = !uiState.isSending
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(uiState.messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    viewModel = viewModel
                )
            }

            if (uiState.isSending) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
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
private fun MessageBubble(
    message: Message,
    viewModel: ChatViewModel
) {
    val isUser = message.sender == MessageSender.USER
    val uiState by viewModel.uiState.collectAsState()
    
    // Tool approval message styling
    if (message.type == MessageType.TOOL_APPROVAL_REQUEST) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    
                    val toolApproval = message.toolApproval
                    if (toolApproval != null) {
                        // Show buttons if not yet decided
                        if (toolApproval.approved == null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Get available options from the ViewModel state
                            val options = uiState.pendingApprovalOptions[message.id]
                            android.util.Log.d("ChatScreen", "Rendering approval for message ${message.id}, options: ${options?.size}")
                            
                            if (!options.isNullOrEmpty()) {
                                // Display all available options dynamically
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    options.forEach { option ->
                                        val isAllowOption = option.kind.startsWith("allow")
                                        val isRejectOption = option.kind.startsWith("reject")
                                        
                                        if (isAllowOption) {
                                            // Allow options (green button)
                                            Button(
                                                onClick = { viewModel.approveTool(message.id, option.optionId) },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color(0xFF4CAF50)
                                                )
                                            ) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = option.name,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(option.name)
                                            }
                                        } else if (isRejectOption) {
                                            // Reject options (outlined red button)
                                            OutlinedButton(
                                                onClick = { viewModel.approveTool(message.id, option.optionId) },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = ButtonDefaults.outlinedButtonColors(
                                                    contentColor = Color(0xFFF44336)
                                                )
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = option.name,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(option.name)
                                            }
                                        } else {
                                            // Unknown option kind (default button)
                                            Button(
                                                onClick = { viewModel.approveTool(message.id, option.optionId) },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(option.name)
                                            }
                                        }
                                    }
                                    
                                    // Always show a cancel button at the bottom
                                    Spacer(modifier = Modifier.height(4.dp))
                                    OutlinedButton(
                                        onClick = { viewModel.rejectTool(message.id) },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.outline
                                        )
                                    ) {
                                        Text("Cancel")
                                    }
                                }
                            } else {
                                // Fallback to default approve/reject if no options available
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = { viewModel.approveTool(message.id, "allow_once") },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF4CAF50)
                                        )
                                    ) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Approve",
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Approve")
                                    }
                                    
                                    OutlinedButton(
                                        onClick = { viewModel.rejectTool(message.id) },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = Color(0xFFF44336)
                                        )
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Reject",
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Reject")
                                    }
                                }
                            }
                        } else {
                            // Show approval status
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (toolApproval.approved) Icons.Default.Check else Icons.Default.Close,
                                    contentDescription = null,
                                    tint = if (toolApproval.approved) Color(0xFF4CAF50) else Color(0xFFF44336),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (toolApproval.approved) "Approved" else "Rejected",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (toolApproval.approved) Color(0xFF4CAF50) else Color(0xFFF44336)
                                )
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // Regular text message
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isUser) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                
                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                Text(
                    text = timeFormat.format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUser) {
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    },
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
private fun MessageInputBar(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onVoiceInput: () -> Unit,
    isRecording: Boolean,
    isCorrectionPending: Boolean,
    enabled: Boolean
) {
    Surface(
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextField(
                value = inputText,
                onValueChange = onInputTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.message_hint)) },
                enabled = enabled,
                maxLines = 4
            )

            AnimatedContent(
                targetState = inputText.isEmpty(),
                label = "voice_send_toggle"
            ) { isEmpty ->
                if (isEmpty) {
                    IconButton(
                        onClick = onVoiceInput,
                        enabled = enabled && !isCorrectionPending
                    ) {
                        when {
                            isRecording -> Icon(
                                Icons.Filled.Mic,
                                contentDescription = "Stop recording",
                                tint = MaterialTheme.colorScheme.error
                            )
                            isCorrectionPending -> CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                            else -> Text(
                                "🎙️",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                } else {
                    IconButton(
                        onClick = onSendMessage,
                        enabled = enabled && inputText.isNotBlank()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.send_message)
                        )
                    }
                }
            }
        }
    }
}
