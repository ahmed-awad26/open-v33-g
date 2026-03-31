package com.opencontacts.feature.dialer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class SimOption(val label: String, val slotIndex: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoDialerRoute(
    onBack: () -> Unit,
    onInitiateCall: (phoneNumber: String, simSlot: Int, speakerEnabled: Boolean) -> Unit,
    availableSims: List<SimOption> = emptyList(),
) {
    val context = LocalContext.current
    val session by AutoDialerStateMachine.session.collectAsState()
    var phoneNumber by remember { mutableStateOf("") }
    var simSlotIndex by remember { mutableIntStateOf(-1) }
    var callDurationMinutes by remember { mutableIntStateOf(0) }
    var callDurationSeconds by remember { mutableIntStateOf(30) }
    var numberOfCalls by remember { mutableIntStateOf(3) }
    var intervalSeconds by remember { mutableIntStateOf(10) }
    var speakerEnabled by remember { mutableStateOf(false) }
    var editField by remember { mutableStateOf<String?>(null) }
    var editValue by remember { mutableStateOf("") }

    val totalDuration = callDurationMinutes * 60 + callDurationSeconds
    val runningStates = listOf(
        AutoDialerStateMachine.State.DIALING,
        AutoDialerStateMachine.State.IN_CALL,
        AutoDialerStateMachine.State.INTERVAL,
        AutoDialerStateMachine.State.PREPARING,
    )
    val isRunning = session?.state in runningStates

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Auto Dialer", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Status
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (isRunning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(session?.statusMessage ?: "Ready", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    if (isRunning && (session?.remainingSeconds ?: 0) > 0) {
                        LinearProgressIndicator(
                            progress = { (session?.currentCall?.toFloat() ?: 0f) / (session?.totalCalls?.toFloat() ?: 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${session?.remainingSeconds}s remaining · call ${session?.currentCall}/${session?.totalCalls}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Phone number
            SectionCard("Target Number") {
                OutlinedTextField(value = phoneNumber, onValueChange = { phoneNumber = it },
                    label = { Text("Phone number") }, leadingIcon = { Icon(Icons.Default.Phone, null) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !isRunning)
            }

            // SIM
            if (availableSims.isNotEmpty()) {
                SectionCard("SIM Card") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = simSlotIndex == -1, onClick = { simSlotIndex = -1 }, label = { Text("Default") }, enabled = !isRunning)
                        availableSims.forEachIndexed { idx, sim ->
                            FilterChip(selected = simSlotIndex == idx, onClick = { simSlotIndex = idx }, label = { Text(sim.label) }, enabled = !isRunning)
                        }
                    }
                }
            }

            // Duration
            SectionCard("Call Duration (max 5 min protection)") {
                Text("Total: ${if (totalDuration > 0) "${totalDuration}s" else "5 min cap"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ThermostatRow("Minutes", callDurationMinutes.toFloat(), 0f..4f, 3, "${callDurationMinutes}m", !isRunning, { callDurationMinutes = it.toInt() }, { editField = "dur_min"; editValue = callDurationMinutes.toString() })
                ThermostatRow("Seconds", callDurationSeconds.toFloat(), 0f..59f, 58, "${callDurationSeconds}s", !isRunning, { callDurationSeconds = it.toInt() }, { editField = "dur_sec"; editValue = callDurationSeconds.toString() })
                if (totalDuration > 300) Text("⚠ Capped at 5 min", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }

            // Number of calls
            SectionCard("Number of Calls") {
                ThermostatRow("Calls", numberOfCalls.toFloat(), 1f..20f, 18, "${numberOfCalls}×", !isRunning, { numberOfCalls = it.toInt() }, { editField = "num_calls"; editValue = numberOfCalls.toString() })
            }

            // Interval
            SectionCard("Interval Between Calls") {
                ThermostatRow("Seconds", intervalSeconds.toFloat(), 3f..120f, 116, "${intervalSeconds}s", !isRunning, { intervalSeconds = it.toInt() }, { editField = "interval"; editValue = intervalSeconds.toString() })
            }

            // Speaker
            SectionCard("Options") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Speaker On During Calls", style = MaterialTheme.typography.bodyMedium)
                        Text("Hear if the other side answers", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = speakerEnabled, onCheckedChange = { speakerEnabled = it }, enabled = !isRunning)
                }
            }

            // Buttons
            if (isRunning) {
                Button(onClick = { AutoDialerStateMachine.abort() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop")
                }
            } else {
                Button(
                    onClick = {
                        if (phoneNumber.isBlank()) return@Button
                        AutoDialerStateMachine.start(context, AutoDialerStateMachine.Session(
                            phoneNumber = phoneNumber, simSlotIndex = simSlotIndex,
                            totalCalls = numberOfCalls, callDurationSeconds = totalDuration,
                            intervalSeconds = intervalSeconds, speakerEnabled = speakerEnabled,
                        ))
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = phoneNumber.isNotBlank()
                ) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Start Auto Dial")
                }
                if (session?.state in listOf(AutoDialerStateMachine.State.COMPLETED, AutoDialerStateMachine.State.ABORTED)) {
                    OutlinedButton(onClick = { AutoDialerStateMachine.reset() }, modifier = Modifier.fillMaxWidth()) { Text("Reset") }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (editField != null) {
        AlertDialog(
            onDismissRequest = { editField = null },
            title = { Text("Enter value") },
            text = { OutlinedTextField(value = editValue, onValueChange = { editValue = it.filter(Char::isDigit) }, singleLine = true, label = { Text("Value") }) },
            confirmButton = {
                TextButton(onClick = {
                    val v = editValue.toIntOrNull() ?: 0
                    when (editField) {
                        "dur_min" -> callDurationMinutes = v.coerceIn(0, 4)
                        "dur_sec" -> callDurationSeconds = v.coerceIn(0, 59)
                        "num_calls" -> numberOfCalls = v.coerceIn(1, 99)
                        "interval" -> intervalSeconds = v.coerceIn(3, 300)
                    }
                    editField = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { editField = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
internal fun ThermostatRow(
    label: String, value: Float, valueRange: ClosedFloatingPointRange<Float>, steps: Int,
    displayValue: String, enabled: Boolean = true, onValueChange: (Float) -> Unit, onEditNumeric: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(60.dp))
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps, enabled = enabled, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onEditNumeric, enabled = enabled, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp), modifier = Modifier.widthIn(min = 52.dp)) {
            Text(displayValue, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
    }
}
