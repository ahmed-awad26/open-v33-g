package com.opencontacts.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opencontacts.core.common.CallRecordingItem
import com.opencontacts.core.common.listCallRecordings
import com.opencontacts.core.ui.localization.localizedText

@Composable
fun RecordingsRoute(
    onBack: () -> Unit,
    appViewModel: AppViewModel,
) {
    val context = LocalContext.current
    val settings by appViewModel.appLockSettings.collectAsStateWithLifecycle()
    val recordings by produceState(initialValue = emptyList<CallRecordingItem>(), settings.recordingsFolderUri, settings.recordingsPath) {
        value = listCallRecordings(context, settings.recordingsFolderUri, settings.recordingsPath)
    }
    SettingsScaffold(title = localizedText("Recordings"), onBack = onBack) { modifier ->
        LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (recordings.isEmpty()) {
                item {
                    EmptyStateCard(
                        title = localizedText("No recordings found"),
                        subtitle = localizedText("Saved call recordings will appear here once they are created in the selected recordings folder."),
                    )
                }
            } else {
                items(recordings, key = { it.id }) { recording ->
                    RecordingCard(recording = recording, onOpen = { openRecording(context, recording.uri) })
                }
            }
        }
    }
}

@Composable
private fun RecordingCard(recording: CallRecordingItem, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.LibraryMusic, contentDescription = null)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(recording.contactName ?: localizedText("Unknown contact"), style = MaterialTheme.typography.titleMedium)
                    Text(recording.phoneNumber ?: recording.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        buildString {
                            append(formatTime(recording.timestampMillis ?: recording.modifiedAt))
                            if (recording.sizeBytes > 0) append(" • ").append(readableFileSize(recording.sizeBytes))
                            append(" • ").append(recording.sourceLabel)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyStateCard(title: String, subtitle: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun openRecording(context: android.content.Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "audio/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ContextCompat.startActivity(context, intent, null)
}

private fun readableFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    return when {
        bytes >= mb -> String.format(java.util.Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(java.util.Locale.US, "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

private fun formatTime(value: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(value))
