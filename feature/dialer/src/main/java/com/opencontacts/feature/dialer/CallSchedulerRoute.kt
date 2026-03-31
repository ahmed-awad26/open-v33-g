package com.opencontacts.feature.dialer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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
import java.text.SimpleDateFormat
import java.util.*

/**
 * CallScheduler – schedule one-off calls with:
 *   - Contact + phone number picker
 *   - SIM chooser
 *   - Date + Time picker
 *   - Pre-call alarm: vibrate/ring X minutes before, user taps to confirm dial
 *   - WorkManager-backed reminder
 */

data class ScheduledCall(
    val id: Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
    val contactName: String,
    val phoneNumber: String,
    val simSlotIndex: Int = -1,
    val scheduledAtMillis: Long,
    val reminderMinutesBefore: Int = 5,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallSchedulerRoute(
    onBack: () -> Unit,
    availableSims: List<SimOption> = emptyList(),
    scheduledCalls: List<ScheduledCall> = emptyList(),
    onScheduleCall: (ScheduledCall) -> Unit,
    onCancelCall: (ScheduledCall) -> Unit,
) {
    val context = LocalContext.current

    var phoneNumber by remember { mutableStateOf("") }
    var contactName by remember { mutableStateOf("") }
    var simSlotIndex by remember { mutableIntStateOf(-1) }
    var reminderMinutes by remember { mutableIntStateOf(5) }

    // Date + time state
    val calendar = remember { Calendar.getInstance() }
    var selectedYear by remember { mutableIntStateOf(calendar.get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableIntStateOf(calendar.get(Calendar.MONTH)) }
    var selectedDay by remember { mutableIntStateOf(calendar.get(Calendar.DAY_OF_MONTH)) }
    var selectedHour by remember { mutableIntStateOf(calendar.get(Calendar.HOUR_OF_DAY)) }
    var selectedMinute by remember { mutableIntStateOf(calendar.get(Calendar.MINUTE) + 5) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val formatter = remember { SimpleDateFormat("EEE, dd MMM yyyy  HH:mm", Locale.getDefault()) }
    val scheduledCalendar = remember(selectedYear, selectedMonth, selectedDay, selectedHour, selectedMinute) {
        Calendar.getInstance().apply {
            set(selectedYear, selectedMonth, selectedDay, selectedHour, selectedMinute, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Call Scheduler", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Target ─────────────────────────────────────────────────
            Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Target", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = contactName,
                        onValueChange = { contactName = it },
                        label = { Text("Contact name (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = phoneNumber,
                        onValueChange = { phoneNumber = it },
                        label = { Text("Phone number") },
                        leadingIcon = { Icon(Icons.Default.Phone, null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            // ── SIM ────────────────────────────────────────────────────
            if (availableSims.isNotEmpty()) {
                Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("SIM", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = simSlotIndex == -1, onClick = { simSlotIndex = -1 }, label = { Text("Default") })
                            availableSims.forEachIndexed { idx, sim ->
                                FilterChip(selected = simSlotIndex == idx, onClick = { simSlotIndex = idx }, label = { Text(sim.label) })
                            }
                        }
                    }
                }
            }

            // ── Date & Time ────────────────────────────────────────────
            Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Scheduled Time", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Text(formatter.format(scheduledCalendar.time), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.CalendarToday, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Date")
                        }
                        OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Schedule, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Time")
                        }
                    }
                }
            }

            // ── Pre-Call Alarm ─────────────────────────────────────────
            Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pre-Call Reminder", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Ring $reminderMinutes min before – tap notification to confirm dial",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = reminderMinutes.toFloat(),
                            onValueChange = { reminderMinutes = it.toInt() },
                            valueRange = 1f..30f,
                            steps = 28,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("${reminderMinutes}m", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // ── Schedule button ────────────────────────────────────────
            Button(
                onClick = {
                    if (phoneNumber.isBlank()) return@Button
                    val scheduled = ScheduledCall(
                        contactName = contactName.ifBlank { phoneNumber },
                        phoneNumber = phoneNumber,
                        simSlotIndex = simSlotIndex,
                        scheduledAtMillis = scheduledCalendar.timeInMillis,
                        reminderMinutesBefore = reminderMinutes,
                    )
                    onScheduleCall(scheduled)
                    scheduleCallAlarm(context, scheduled)
                    phoneNumber = ""
                    contactName = ""
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = phoneNumber.isNotBlank() && scheduledCalendar.timeInMillis > System.currentTimeMillis()
            ) {
                Icon(Icons.Default.Alarm, null)
                Spacer(Modifier.width(6.dp))
                Text("Schedule Call")
            }

            // ── Scheduled Calls List ───────────────────────────────────
            if (scheduledCalls.isNotEmpty()) {
                Text("Upcoming Calls", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                scheduledCalls.sortedBy { it.scheduledAtMillis }.forEach { call ->
                    ScheduledCallCard(call = call, formatter = formatter, onCancel = {
                        cancelCallAlarm(context, call)
                        onCancelCall(call)
                    })
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    // Date picker dialog
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = scheduledCalendar.timeInMillis
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val cal = Calendar.getInstance().apply { timeInMillis = millis }
                        selectedYear = cal.get(Calendar.YEAR)
                        selectedMonth = cal.get(Calendar.MONTH)
                        selectedDay = cal.get(Calendar.DAY_OF_MONTH)
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = datePickerState) }
    }

    // Time picker dialog
    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = selectedHour,
            initialMinute = selectedMinute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select Time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    selectedHour = timePickerState.hour
                    selectedMinute = timePickerState.minute
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ScheduledCallCard(
    call: ScheduledCall,
    formatter: SimpleDateFormat,
    onCancel: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(call.contactName, fontWeight = FontWeight.SemiBold)
                Text(call.phoneNumber, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatter.format(Date(call.scheduledAtMillis)), style = MaterialTheme.typography.labelSmall)
                Text("Reminder: ${call.reminderMinutesBefore} min before", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Cancel, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ── Alarm helpers ─────────────────────────────────────────────────────────────

const val ACTION_SCHEDULED_CALL_REMINDER = "com.opencontacts.app.SCHEDULED_CALL_REMINDER"
const val ACTION_SCHEDULED_CALL_DIAL = "com.opencontacts.app.SCHEDULED_CALL_DIAL"
const val EXTRA_PHONE_NUMBER = "phone_number"
const val EXTRA_CONTACT_NAME = "contact_name"
const val EXTRA_SIM_SLOT = "sim_slot"
const val EXTRA_CALL_ID = "call_id"

fun scheduleCallAlarm(context: Context, call: ScheduledCall) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    // Reminder alarm (pre-call notification)
    val reminderMillis = call.scheduledAtMillis - call.reminderMinutesBefore * 60_000L
    if (reminderMillis > System.currentTimeMillis()) {
        val intent = Intent(ACTION_SCHEDULED_CALL_REMINDER).apply {
            putExtra(EXTRA_PHONE_NUMBER, call.phoneNumber)
            putExtra(EXTRA_CONTACT_NAME, call.contactName)
            putExtra(EXTRA_SIM_SLOT, call.simSlotIndex)
            putExtra(EXTRA_CALL_ID, call.id)
            setPackage(context.packageName)
        }
        val pi = PendingIntent.getBroadcast(
            context, call.id * 2,
            intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminderMillis, pi)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, reminderMillis, pi)
        }
    }

    // Dial alarm (at scheduled time)
    val dialIntent = Intent(ACTION_SCHEDULED_CALL_DIAL).apply {
        putExtra(EXTRA_PHONE_NUMBER, call.phoneNumber)
        putExtra(EXTRA_CONTACT_NAME, call.contactName)
        putExtra(EXTRA_SIM_SLOT, call.simSlotIndex)
        putExtra(EXTRA_CALL_ID, call.id)
        setPackage(context.packageName)
    }
    val dialPi = PendingIntent.getBroadcast(
        context, call.id * 2 + 1,
        dialIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, call.scheduledAtMillis, dialPi)
    } else {
        alarmManager.set(AlarmManager.RTC_WAKEUP, call.scheduledAtMillis, dialPi)
    }
}

fun cancelCallAlarm(context: Context, call: ScheduledCall) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val reminderPi = PendingIntent.getBroadcast(
        context, call.id * 2,
        Intent(ACTION_SCHEDULED_CALL_REMINDER).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val dialPi = PendingIntent.getBroadcast(
        context, call.id * 2 + 1,
        Intent(ACTION_SCHEDULED_CALL_DIAL).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    alarmManager.cancel(reminderPi)
    alarmManager.cancel(dialPi)
}
