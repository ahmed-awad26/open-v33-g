package com.opencontacts.app

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugAnalyticsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Auto Dial", "Telecom", "Flash", "System")

    // Live state
    val dialSession by AutoDialerStateMachine.session.collectAsState()
    val activeCall by TelecomCallCoordinator.activeCall.collectAsState()
    val telecomState by TelecomCallCoordinator.telecomState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Debug Analytics", fontWeight = FontWeight.SemiBold)
                        Surface(color = Color(0xFFFF6F00), shape = RoundedCornerShape(4.dp)) {
                            Text("DEV", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = { refreshKey++ }) { Icon(Icons.Default.Refresh, "Refresh") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Tab row
            ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 8.dp) {
                tabs.forEachIndexed { idx, tab ->
                    Tab(selected = selectedTab == idx, onClick = { selectedTab = idx }, text = { Text(tab) })
                }
            }

            LazyColumn(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (selectedTab) {
                    0 -> {
                        // ── Auto Dialer State ───────────────────────────────────
                        item { DebugSectionHeader("Auto Dialer State Machine") }
                        item {
                            val s = dialSession
                            if (s == null) {
                                DebugCard("Session") { DebugRow("Status", "IDLE / No active session") }
                            } else {
                                DebugCard("Session") {
                                    DebugRow("State", s.state.name)
                                    DebugRow("Target", "${s.phoneNumber} (SIM slot: ${if (s.simSlotIndex < 0) "default" else "${s.simSlotIndex}"})")
                                    DebugRow("Progress", "Call ${s.currentCall} / ${s.totalCalls}")
                                    DebugRow("Duration cap", "${minOf(s.callDurationSeconds, 300)}s (watchdog: 300s)")
                                    DebugRow("Interval", "${s.intervalSeconds}s between calls")
                                    DebugRow("Speaker", "${s.speakerEnabled}")
                                    DebugRow("Remaining", "${s.remainingSeconds}s")
                                    DebugRow("Status msg", s.statusMessage)
                                }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (dialSession?.state in listOf(
                                    AutoDialerStateMachine.State.DIALING,
                                    AutoDialerStateMachine.State.IN_CALL,
                                    AutoDialerStateMachine.State.INTERVAL,
                                )) {
                                    OutlinedButton(onClick = { AutoDialerStateMachine.abort() }) {
                                        Icon(Icons.Default.Stop, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Force abort")
                                    }
                                }
                                if (dialSession != null) {
                                    OutlinedButton(onClick = { AutoDialerStateMachine.reset() }) {
                                        Text("Reset")
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        // ── Telecom State ───────────────────────────────────────
                        item { DebugSectionHeader("Telecom / InCall State") }
                        item {
                            DebugCard("Active Call") {
                                if (activeCall == null) {
                                    DebugRow("Status", "No active call")
                                } else {
                                    DebugRow("Display name", activeCall!!.displayName)
                                    DebugRow("Number", activeCall!!.number)
                                    DebugRow("SIM label", activeCall!!.phoneAccountLabel ?: "—")
                                }
                            }
                        }
                        item {
                            DebugCard("Audio / Control State") {
                                DebugRow("Call state", callStateName(telecomState.state))
                                DebugRow("Muted", "${telecomState.isMuted}")
                                DebugRow("Audio route", telecomState.currentRouteLabel)
                                DebugRow("On hold", "${telecomState.isOnHold}")
                                DebugRow("Can hold", "${telecomState.canHold}")
                                DebugRow("Can merge", "${telecomState.canMerge}")
                                DebugRow("Call count", "${telecomState.callCount}")
                                DebugRow("Can DTMF", "${telecomState.canDtmf}")
                                telecomState.connectedAtMillis?.let { ts ->
                                    val elapsed = ((System.currentTimeMillis() - ts) / 1000).toInt()
                                    DebugRow("Call duration", "${elapsed}s")
                                }
                            }
                        }
                        item {
                            DebugCard("Call Timer (auto-end)") {
                                DebugRow("Timer job active", "Check CallTimerManager")
                            }
                        }
                    }
                    2 -> {
                        // ── Flash & Camera ──────────────────────────────────────
                        item { DebugSectionHeader("Flash Alert") }
                        item {
                            DebugCard("FlashAlertManager") {
                                DebugRow("Status", "Controlled by FlashAlertManager.startFlashing/stopFlashing")
                                DebugRow("Trigger", "PHONE_STATE = RINGING + flashAlertOnIncomingCall = true")
                                DebugRow("Pattern", "3×(120ms on + 120ms off) + 800ms pause, repeat")
                                DebugRow("Stop on", "OFFHOOK or IDLE state")
                                DebugRow("Torch backend", "CameraManager.setTorchMode (rear camera)")
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { FlashAlertManager.startFlashing(context) }) {
                                    Icon(Icons.Default.FlashOn, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Test Flash")
                                }
                                OutlinedButton(onClick = { FlashAlertManager.stopFlashing(context) }) {
                                    Text("Stop Flash")
                                }
                            }
                        }
                    }
                    3 -> {
                        // ── System ──────────────────────────────────────────────
                        item { DebugSectionHeader("System / Permissions") }
                        item {
                            val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                            val isDefaultDialer = tm?.defaultDialerPackage == context.packageName
                            val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
                            val canSchedule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                (context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager)?.canScheduleExactAlarms() ?: false
                            } else true

                            DebugCard("Environment") {
                                DebugRow("Android", "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
                                DebugRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                                DebugRow("Package", context.packageName)
                                DebugRow("Default dialer", "${isDefaultDialer}")
                                DebugRow("Draw overlays", "$canOverlay")
                                DebugRow("Exact alarms", "$canSchedule")
                            }
                        }
                        item {
                            DebugCard("Build Info") {
                                DebugRow("Min SDK", "26 (Android 8.0)")
                                DebugRow("Target SDK", "35 (Android 15)")
                                DebugRow("New features (v33)", "Flash SMS PDU, Mini call chip, AutoDialer SM, Phone Normalizer, SMS Composer, Permission Checker, Folder Top Strip, Call Merge, Debug Screen")
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

// ── Debug UI primitives ───────────────────────────────────────────────────────

@Composable
private fun DebugSectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun DebugCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            content()
        }
    }
}

@Composable
private fun DebugRow(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun callStateName(state: Int): String = when (state) {
    android.telecom.Call.STATE_ACTIVE -> "ACTIVE"
    android.telecom.Call.STATE_HOLDING -> "HOLDING"
    android.telecom.Call.STATE_DIALING -> "DIALING"
    android.telecom.Call.STATE_RINGING -> "RINGING"
    android.telecom.Call.STATE_CONNECTING -> "CONNECTING"
    android.telecom.Call.STATE_DISCONNECTED -> "DISCONNECTED"
    android.telecom.Call.STATE_DISCONNECTING -> "DISCONNECTING"
    else -> "UNKNOWN($state)"
}
