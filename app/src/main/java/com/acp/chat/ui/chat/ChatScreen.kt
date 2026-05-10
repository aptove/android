package com.acp.chat.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.acp.chat.R
import com.acp.chat.data.model.Message
import com.acp.chat.data.model.MessageSender
import com.acp.chat.data.model.MessageStatus
import com.acp.chat.data.model.MessageType
import com.agentclientprotocol.model.AvailableCommand
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
    var showMemoryDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val systemLang = java.util.Locale.getDefault().language
    val defaultVoiceLang = if (systemLang == "tr") "tr-TR" else "en-US"
    val voiceLanguage = remember { prefs.getString("voice_language", null) ?: defaultVoiceLang }

    LaunchedEffect(voiceLanguage) {
        voiceInputViewModel.updateLocale(voiceLanguage)
        viewModel.setVoiceLanguage(voiceLanguage)
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceInputViewModel.startRecording()
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.onImagesSelected(uris)
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
                enabled = !uiState.isSending,
                selectedImages = uiState.selectedImageUris,
                onPickImages = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onRemoveImage = viewModel::removeImage,
                commandSuggestions = uiState.commandSuggestions,
                onCommandSelected = viewModel::selectCommand,
                onToggleCommandPicker = viewModel::toggleCommandPicker,
                hasAvailableCommands = uiState.availableCommands.isNotEmpty(),
                showAttachmentPanel = uiState.showAttachmentPanel,
                onToggleAttachmentPanel = viewModel::toggleAttachmentPanel,
                onOpenMemory = { showMemoryDialog = true }
            )
            if (showMemoryDialog) {
                MemoryEntryDialog(
                    onDismiss = { showMemoryDialog = false },
                    onSave = { text -> viewModel.sendMemoryEntry(text); showMemoryDialog = false },
                    onCorrectTranscript = { viewModel.correctTranscript(it) }
                )
            }
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
            title = { Text(stringResource(R.string.error)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    viewModel: ChatViewModel
) {
    val isUser = message.sender == MessageSender.USER
    val uiState by viewModel.uiState.collectAsState()

    // Slash-command chip
    if (message.type == MessageType.SLASH_COMMAND) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            SlashCommandChip(text = message.text)
        }
        return
    }

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
                                        Text(stringResource(R.string.cancel))
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
                                            contentDescription = stringResource(R.string.chat_approve),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.chat_approve))
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
                                            contentDescription = stringResource(R.string.chat_reject),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.chat_reject))
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
    val isError = message.status == MessageStatus.ERROR && !message.error.isNullOrBlank()
    val displayText = if (message.text.isBlank() && isError) message.error!! else message.text
    val imageUris = if (isUser) uiState.imageUrisByMessageId[message.id] else null
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (!isUser && displayText.isNotBlank()) {
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("message", displayText))
                            }
                        )
                    } else Modifier
                ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Image thumbnails above text
                if (!imageUris.isNullOrEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(bottom = if (displayText.isNotBlank()) 8.dp else 0.dp)
                    ) {
                        items(imageUris) { uri ->
                            AsyncImage(
                                model = uri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(80.dp)
                                    .background(
                                        Color.Black.copy(alpha = 0.1f),
                                        RoundedCornerShape(8.dp)
                                    )
                            )
                        }
                    }
                }

                if (displayText.isNotBlank()) {
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isUser) {
                            MaterialTheme.colorScheme.onPrimary
                        } else if (isError) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

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

@Composable
private fun SlashCommandChip(text: String) {
    Surface(
        shape = androidx.compose.foundation.shape.CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "/",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = text.trimStart('/').trim(),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CommandSuggestionBar(
    suggestions: List<AvailableCommand>,
    onCommandSelected: (AvailableCommand) -> Unit
) {
    if (suggestions.isEmpty()) return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp),
        tonalElevation = 6.dp,
        shadowElevation = 4.dp
    ) {
        LazyColumn {
            items(suggestions, key = { it.name }) { command ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = "/${command.name}",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        )
                    },
                    supportingContent = {
                        Text(
                            text = command.description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { onCommandSelected(command) })
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AttachmentPanel(
    onPickImages: () -> Unit,
    onToggleCommandPicker: () -> Unit,
    onOpenMemory: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        AttachmentTile(
            icon = { Icon(Icons.Default.Image, contentDescription = null, tint = Color.White) },
            label = "Photos",
            color = MaterialTheme.colorScheme.primary,
            onClick = onPickImages
        )
        AttachmentTile(
            icon = {
                Text(
                    "/",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace)
                )
            },
            label = "Commands",
            color = MaterialTheme.colorScheme.secondary,
            onClick = onToggleCommandPicker
        )
        AttachmentTile(
            icon = { Text("🧠", style = MaterialTheme.typography.titleLarge) },
            label = "Memory",
            color = MaterialTheme.colorScheme.tertiary,
            onClick = onOpenMemory
        )
    }
}

@Composable
private fun AttachmentTile(
    icon: @Composable () -> Unit,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(color)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
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
    enabled: Boolean,
    selectedImages: List<Uri>,
    onPickImages: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    commandSuggestions: List<AvailableCommand> = emptyList(),
    onCommandSelected: (AvailableCommand) -> Unit = {},
    onToggleCommandPicker: () -> Unit = {},
    hasAvailableCommands: Boolean = false,
    showAttachmentPanel: Boolean = false,
    onToggleAttachmentPanel: () -> Unit = {},
    onOpenMemory: () -> Unit = {}
) {
    Surface(
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            AnimatedVisibility(visible = showAttachmentPanel) {
                AttachmentPanel(
                    onPickImages = { onPickImages(); onToggleAttachmentPanel() },
                    onToggleCommandPicker = { onToggleCommandPicker(); onToggleAttachmentPanel() },
                    onOpenMemory = { onOpenMemory(); onToggleAttachmentPanel() }
                )
            }
            CommandSuggestionBar(
                suggestions = commandSuggestions,
                onCommandSelected = onCommandSelected
            )
            // Image thumbnail strip
            if (selectedImages.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    itemsIndexed(selectedImages) { index, uri ->
                        Box {
                            AsyncImage(
                                model = uri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(8.dp)
                                    )
                            )
                            IconButton(
                                onClick = { onRemoveImage(index) },
                                modifier = Modifier
                                    .size(20.dp)
                                    .align(Alignment.TopEnd)
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                        RoundedCornerShape(10.dp)
                                    )
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove image",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onToggleAttachmentPanel) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Attachment menu",
                        tint = if (showAttachmentPanel) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurface
                    )
                }

                TextField(
                    value = inputText,
                    onValueChange = onInputTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.message_hint)) },
                    enabled = enabled,
                    maxLines = 4
                )

                AnimatedContent(
                    targetState = inputText.isEmpty() && selectedImages.isEmpty(),
                    label = "voice_send_toggle"
                ) { showMic ->
                    if (showMic) {
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
                            enabled = enabled && (inputText.isNotBlank() || selectedImages.isNotEmpty())
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
}

@Composable
private fun MemoryEntryDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onCorrectTranscript: suspend (String) -> String,
    voiceInputViewModel: VoiceInputViewModel = hiltViewModel()
) {
    var text by remember { mutableStateOf("") }
    var isCorrectingTranscript by remember { mutableStateOf(false) }
    val voiceState by voiceInputViewModel.recordingState.collectAsState()
    val rawTranscript by voiceInputViewModel.rawTranscript.collectAsState()

    // When voice recognition finishes, run AI correction then append result
    LaunchedEffect(rawTranscript) {
        rawTranscript?.let { transcript ->
            voiceInputViewModel.clearTranscript()
            isCorrectingTranscript = true
            val corrected = onCorrectTranscript(transcript)
            text += (if (text.isEmpty()) "" else " ") + corrected
            isCorrectingTranscript = false
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) voiceInputViewModel.startRecording()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("What do you want the AI to remember?") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    minLines = 4
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isRecording = voiceState is RecordingState.Recording
                    IconButton(
                        onClick = {
                            if (isRecording) {
                                voiceInputViewModel.stopRecording()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !isCorrectingTranscript
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = if (isRecording) "Stop recording" else "Start recording",
                            tint = if (isRecording) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.primary
                        )
                    }
                    if (isCorrectingTranscript) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = when {
                            isCorrectingTranscript -> "Correcting…"
                            voiceState is RecordingState.Recording -> "Recording…"
                            voiceState is RecordingState.Processing -> "Transcribing…"
                            else -> "Tap mic to dictate"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isCorrectingTranscript || voiceState is RecordingState.Recording)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onSave(text.trim()) },
                enabled = text.isNotBlank() && !isCorrectingTranscript
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
