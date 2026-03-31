package com.opencontacts.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class SettingsEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: String,
    val accent: Color,
    val group: String,
)

private val settingsEntries = listOf(
    // ── Vault & Security ──────────────────────────────────────────────
    SettingsEntry("Vaults", "Create, lock, and manage encrypted vaults", Icons.Default.FolderSpecial, "vaults", Color(0xFF1976D2), "Vault"),
    SettingsEntry("Security", "PIN, biometrics, lock timing and lock screen", Icons.Default.Shield, "settings/security", Color(0xFF388E3C), "Vault"),
    SettingsEntry("Groups & Tags", "Folders and tags for the active vault", Icons.Default.Label, "workspace", Color(0xFF7B1FA2), "Vault"),

    // ── Calling & Comms ───────────────────────────────────────────────
    SettingsEntry("Calling & Notifications", "Default dialer, overlays, call role, recording", Icons.Default.Call, "settings/notifications", Color(0xFF0097A7), "Calling"),
    SettingsEntry("Auto Dialer", "Repeat-dial with timer, interval and speaker", Icons.Default.Replay, "settings/autodialer", Color(0xFFF57C00), "Calling"),
    SettingsEntry("Call Scheduler", "Schedule future calls with pre-call reminders", Icons.Default.EventNote, "settings/callscheduler", Color(0xFF5D4037), "Calling"),
    SettingsEntry("Flash SMS", "Class 0 pop-up messages — not saved in inbox", Icons.Default.Bolt, "settings/flashsms", Color(0xFFC62828), "Calling"),
    SettingsEntry("Recordings", "Browse saved call recordings", Icons.Default.Mic, "settings/recordings", Color(0xFF00838F), "Calling"),

    // ── Contacts ─────────────────────────────────────────────────────
    SettingsEntry("Import & Export", "Import VCF/CSV, export, backup & restore", Icons.Default.SwapVert, "settings/importexport", Color(0xFF558B2F), "Contacts"),
    SettingsEntry("Blocked Contacts", "Manage blocked numbers", Icons.Default.Block, "settings/blocked", Color(0xFFB71C1C), "Contacts"),
    SettingsEntry("Trash", "Restore or permanently delete contacts", Icons.Default.DeleteSweep, "settings/trash", Color(0xFF37474F), "Contacts"),

    // ── Appearance & App ─────────────────────────────────────────────
    SettingsEntry("Appearance", "Theme, accent colors, language, fonts", Icons.Default.Palette, "settings/appearance", Color(0xFFAD1457), "App"),
    SettingsEntry("Preferences", "Sorting, density, dial pad, behavior", Icons.Default.Tune, "settings/preferences", Color(0xFF4527A0), "App"),
    SettingsEntry("SMS Composer", "Send messages with delivery reports and quick templates", Icons.Default.Chat, "settings/smscomposer", Color(0xFF00695C), "Calling"),
    SettingsEntry("Permission Checker", "See which features are blocked and why", Icons.Default.SecurityUpdate, "settings/permissions", Color(0xFF37474F), "App"),
    SettingsEntry("Debug Analytics", "Developer: monitor scheduler, telecom and flash state", Icons.Default.BugReport, "settings/debug", Color(0xFF4E342E), "App"),
    SettingsEntry("About", "Version, privacy scope, notes", Icons.Default.Info, "settings/about", Color(0xFF455A64), "App"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHomeRoute(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val groups = remember { settingsEntries.groupBy { it.group } }
    val groupOrder = listOf("Vault", "Calling", "Contacts", "App")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            groupOrder.forEach { groupName ->
                val entries = groups[groupName] ?: return@forEach
                item(key = "header_$groupName") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        groupName.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                item(key = "group_$groupName") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Column {
                            entries.forEachIndexed { idx, entry ->
                                SettingsRow(entry = entry, onClick = { onNavigate(entry.route) })
                                if (idx < entries.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 68.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                        thickness = 0.5.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SettingsRow(entry: SettingsEntry, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val bgColor by animateColorAsState(
        if (pressed) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        animationSpec = tween(100), label = "press",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Icon badge with accent color
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(entry.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                entry.icon,
                contentDescription = null,
                tint = entry.accent,
                modifier = Modifier.size(22.dp),
            )
        }

        // Title + subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                entry.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        // Chevron
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(18.dp),
        )
    }
}
