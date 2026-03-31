package com.opencontacts.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

// ── Data models ────────────────────────────────────────────────────────────────

enum class PermStatus { GRANTED, DENIED, NOT_REQUIRED }

data class FeatureCheck(
    val featureName: String,
    val description: String,
    val icon: ImageVector,
    val status: PermStatus,
    val blockedBy: List<String>,
    val fixAction: ((Context) -> Unit)? = null,
    val fixLabel: String = "Fix",
)

// ── Permission checking logic ──────────────────────────────────────────────────

fun buildFeatureChecks(context: Context): List<FeatureCheck> {
    fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    val isDefaultDialer = tm?.defaultDialerPackage == context.packageName
    val canDrawOverlays = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    val canScheduleAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
        am?.canScheduleExactAlarms() ?: false
    } else true

    return listOf(
        FeatureCheck(
            featureName = "Incoming call overlay",
            description = "Show caller info on top of other apps",
            icon = Icons.Default.CallReceived,
            status = if (canDrawOverlays || isDefaultDialer) PermStatus.GRANTED else PermStatus.DENIED,
            blockedBy = buildList {
                if (!canDrawOverlays) add("Display over apps not granted")
                if (!isDefaultDialer) add("Not set as default phone app")
            },
            fixAction = { ctx ->
                if (!canDrawOverlays && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            fixLabel = "Open display over apps settings",
        ),
        FeatureCheck(
            featureName = "Flash alert on calls",
            description = "Blink camera torch on incoming calls",
            icon = Icons.Default.FlashOn,
            status = if (hasPermission(Manifest.permission.CAMERA)) PermStatus.GRANTED else PermStatus.DENIED,
            blockedBy = if (!hasPermission(Manifest.permission.CAMERA)) listOf("Camera permission denied") else emptyList(),
            fixAction = { ctx ->
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            fixLabel = "Open app permissions",
        ),
        FeatureCheck(
            featureName = "SMS (send messages)",
            description = "Send regular and Flash SMS",
            icon = Icons.Default.Message,
            status = if (hasPermission(Manifest.permission.SEND_SMS)) PermStatus.GRANTED else PermStatus.DENIED,
            blockedBy = if (!hasPermission(Manifest.permission.SEND_SMS)) listOf("SEND_SMS permission denied") else emptyList(),
            fixAction = { ctx ->
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            fixLabel = "Open app permissions",
        ),
        FeatureCheck(
            featureName = "Mini floating call view",
            description = "Show draggable call chip while using other apps",
            icon = Icons.Default.PictureInPicture,
            status = if (canDrawOverlays) PermStatus.GRANTED else PermStatus.DENIED,
            blockedBy = if (!canDrawOverlays) listOf("Display over apps not granted") else emptyList(),
            fixAction = { ctx ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            fixLabel = "Grant display over apps",
        ),
        FeatureCheck(
            featureName = "Call scheduler (exact timing)",
            description = "Ring at exactly the scheduled time",
            icon = Icons.Default.Alarm,
            status = if (canScheduleAlarms) PermStatus.GRANTED else PermStatus.DENIED,
            blockedBy = if (!canScheduleAlarms) listOf("Exact alarm permission required (Android 12+)") else emptyList(),
            fixAction = { ctx ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            fixLabel = "Allow exact alarms",
        ),
        FeatureCheck(
            featureName = "Read contacts",
            description = "Access system contacts for import and search",
            icon = Icons.Default.Contacts,
            status = if (hasPermission(Manifest.permission.READ_CONTACTS)) PermStatus.GRANTED else PermStatus.DENIED,
            blockedBy = if (!hasPermission(Manifest.permission.READ_CONTACTS)) listOf("READ_CONTACTS denied") else emptyList(),
            fixAction = { ctx ->
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            fixLabel = "Open app permissions",
        ),
        FeatureCheck(
            featureName = "Make phone calls",
            description = "Dial numbers directly from the app",
            icon = Icons.Default.Call,
            status = if (hasPermission(Manifest.permission.CALL_PHONE)) PermStatus.GRANTED else PermStatus.DENIED,
            blockedBy = if (!hasPermission(Manifest.permission.CALL_PHONE)) listOf("CALL_PHONE denied") else emptyList(),
            fixAction = { ctx ->
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            fixLabel = "Open app permissions",
        ),
        FeatureCheck(
            featureName = "Default phone app",
            description = "Full call control, recording, and incoming UI",
            icon = Icons.Default.PhoneInTalk,
            status = if (isDefaultDialer) PermStatus.GRANTED else PermStatus.DENIED,
            blockedBy = if (!isDefaultDialer) listOf("Not set as default dialer") else emptyList(),
            fixAction = { ctx ->
                val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                    .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            },
            fixLabel = "Set as default dialer",
        ),
        FeatureCheck(
            featureName = "Post notifications",
            description = "Show incoming call and missed call alerts",
            icon = Icons.Default.Notifications,
            status = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                hasPermission(Manifest.permission.POST_NOTIFICATIONS)) PermStatus.GRANTED else PermStatus.DENIED,
            blockedBy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) listOf("POST_NOTIFICATIONS denied (Android 13+)") else emptyList(),
            fixAction = { ctx ->
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            fixLabel = "Open notification settings",
        ),
    )
}

// ── UI ─────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionCheckerRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    var checks by remember { mutableStateOf(buildFeatureChecks(context)) }
    var expandedIndex by remember { mutableIntStateOf(-1) }

    val allOk = checks.all { it.status == PermStatus.GRANTED }
    val blocked = checks.count { it.status == PermStatus.DENIED }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Permission Checker", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = { checks = buildFeatureChecks(context) }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Summary banner
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (allOk) Color(0xFF1B5E20).copy(alpha = 0.1f)
                        else MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (allOk) Icons.Default.CheckCircle else Icons.Default.Warning,
                            null,
                            tint = if (allOk) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp),
                        )
                        Column {
                            Text(
                                if (allOk) "All features ready" else "$blocked feature(s) blocked",
                                fontWeight = FontWeight.Bold,
                                color = if (allOk) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                            )
                            Text(
                                if (allOk) "All permissions granted — app is fully functional."
                                else "Tap a blocked item below to fix it.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            // Feature list
            items(checks.indices.toList()) { idx ->
                val check = checks[idx]
                FeatureCheckCard(
                    check = check,
                    expanded = expandedIndex == idx,
                    onClick = { expandedIndex = if (expandedIndex == idx) -1 else idx },
                    onFix = {
                        check.fixAction?.invoke(context)
                        // Re-evaluate after a short delay
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            checks = buildFeatureChecks(context)
                        }, 1000)
                    },
                )
            }
        }
    }
}

@Composable
private fun FeatureCheckCard(
    check: FeatureCheck,
    expanded: Boolean,
    onClick: () -> Unit,
    onFix: () -> Unit,
) {
    val (statusColor, statusIcon) = when (check.status) {
        PermStatus.GRANTED -> Color(0xFF4CAF50) to Icons.Default.CheckCircle
        PermStatus.DENIED -> MaterialTheme.colorScheme.error to Icons.Default.Cancel
        PermStatus.NOT_REQUIRED -> Color(0xFF9E9E9E) to Icons.Default.RemoveCircle
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(40.dp).background(statusColor.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(check.icon, null, tint = statusColor, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(check.featureName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text(check.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    statusIcon,
                    contentDescription = check.status.name,
                    tint = statusColor,
                    modifier = Modifier.size(22.dp),
                )
            }

            // Expanded: blocked reasons + fix button
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (check.blockedBy.isNotEmpty()) {
                        Text("Blocked by:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                        check.blockedBy.forEach { reason ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Warning, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                                Text(reason, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = Color(0xFF4CAF50))
                            Text("No issues — feature is fully available.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF4CAF50))
                        }
                    }
                    if (check.status == PermStatus.DENIED && check.fixAction != null) {
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = onFix, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                            Icon(Icons.Default.Build, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(check.fixLabel)
                        }
                    }
                }
            }
        }
    }
}
