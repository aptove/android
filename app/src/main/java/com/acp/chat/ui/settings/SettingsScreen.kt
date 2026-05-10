package com.acp.chat.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.acp.chat.BuildConfig
import com.acp.chat.R

private data class LanguageOption(val code: String, val nameResId: Int)

private val languages = listOf(
    LanguageOption("en", R.string.language_english),
    LanguageOption("tr", R.string.language_turkish)
)

private val voiceLanguages = listOf(
    LanguageOption("en-US", R.string.language_english),
    LanguageOption("tr-TR", R.string.language_turkish)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    isDarkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    isVerboseMode: Boolean,
    onVerboseModeChange: (Boolean) -> Unit,
    currentLanguage: String,
    onLanguageChange: (String) -> Unit,
    currentVoiceLanguage: String,
    onVoiceLanguageChange: (String) -> Unit
) {
    val context = LocalContext.current
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showVoiceLanguageDialog by remember { mutableStateOf(false) }

    if (showVoiceLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showVoiceLanguageDialog = false },
            title = { Text(stringResource(R.string.settings_voice_language)) },
            text = {
                Column {
                    voiceLanguages.forEach { lang ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onVoiceLanguageChange(lang.code)
                                    showVoiceLanguageDialog = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentVoiceLanguage == lang.code,
                                onClick = {
                                    onVoiceLanguageChange(lang.code)
                                    showVoiceLanguageDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(lang.nameResId))
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column {
                    languages.forEach { lang ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onLanguageChange(lang.code)
                                    showLanguageDialog = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentLanguage == lang.code,
                                onClick = {
                                    onLanguageChange(lang.code)
                                    showLanguageDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(lang.nameResId))
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                Text(
                    text = stringResource(R.string.settings_appearance),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_dark_mode)) },
                    supportingContent = {
                        Text(if (isDarkMode) stringResource(R.string.settings_dark_theme_active) else stringResource(R.string.settings_light_theme_active))
                    },
                    leadingContent = {
                        Icon(Icons.Default.DarkMode, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Switch(
                            checked = isDarkMode,
                            onCheckedChange = onDarkModeChange
                        )
                    },
                    modifier = Modifier.clickable { onDarkModeChange(!isDarkMode) }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_verbose_messages)) },
                    supportingContent = {
                        Text(if (isVerboseMode) stringResource(R.string.settings_verbose_messages_on) else stringResource(R.string.settings_verbose_messages_off))
                    },
                    leadingContent = {
                        Icon(Icons.Default.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Switch(
                            checked = isVerboseMode,
                            onCheckedChange = onVerboseModeChange
                        )
                    },
                    modifier = Modifier.clickable { onVerboseModeChange(!isVerboseMode) }
                )
                HorizontalDivider()
            }

            item {
                Text(
                    text = stringResource(R.string.settings_language),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                )
                HorizontalDivider()
                val currentLangName = languages.find { it.code == currentLanguage }
                    ?.nameResId?.let { stringResource(it) }
                    ?: stringResource(R.string.language_english)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_language)) },
                    supportingContent = { Text(currentLangName) },
                    leadingContent = {
                        Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    },
                    modifier = Modifier.clickable { showLanguageDialog = true }
                )
                HorizontalDivider()
                val currentVoiceLangName = voiceLanguages.find { it.code == currentVoiceLanguage }
                    ?.nameResId?.let { stringResource(it) }
                    ?: stringResource(R.string.language_english)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_voice_language)) },
                    supportingContent = { Text(currentVoiceLangName) },
                    leadingContent = {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    },
                    modifier = Modifier.clickable { showVoiceLanguageDialog = true }
                )
                HorizontalDivider()
            }

            item {
                Text(
                    text = stringResource(R.string.settings_about),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                )
                HorizontalDivider()
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_terms_of_service)) },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    },
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aptove.com/terms-of-service"))
                        context.startActivity(intent)
                    }
                )
                HorizontalDivider()
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_version)) },
                    trailingContent = {
                        Text(
                            BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                )
                HorizontalDivider()
            }
        }
    }
}
