package com.opencontacts.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencontacts.core.common.ContactCallBehaviorPreferences
import com.opencontacts.core.common.ContactSmsJournalEntry
import java.text.SimpleDateFormat
import java.util.*

// ── Message status ─────────────────────────────────────────────────────────────

enum class SmsStatus { PENDING, SENDING, SENT, DELIVERED, FAILED }

data class SmsMessage(
    val id: Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
    val to: String,
    val toName: String = "",
    val body: String,
    val simLabel: String = "Default",
    val sentAt: Long = System.currentTimeMillis(),
    val status: SmsStatus = SmsStatus.PENDING,
    val isFlash: Boolean = false,
)

// ── Quick reply templates ──────────────────────────────────────────────────────

val DEFAULT_SMS_TEMPLATES = listOf(
    "I'll call you back shortly.",
    "I'm busy right now. I'll call later.",
    "Please send me the details by message.",
    "I'm in a meeting. I'll get back to you.",
    "On my way, be there soon!",
    "Can we reschedule? Please suggest a time.",
    "Received. Will respond shortly.",
    "Not available right now. Message me.",
)

// ── Delivery tracking via PendingIntent broadcast ─────────────────────────────

private const val SMS_SENT_ACTION = "com.opencontacts.app.SMS_SENT"
private const val SMS_DELIVERED_ACTION = "com.opencontacts.app.SMS_DELIVERED"
private const val EXTRA_MSG_ID = "msg_id"

/**
 * Send an SMS and track delivery via broadcast callbacks.
 */
@Suppress("DEPRECATION")
fun sendSmsWithTracking(
    context: Context,
    msg: SmsMessage,
    subscriptionId: Int = -1,
    contactId: String? = null,
    onStatusChange: (Int, SmsStatus) -> Unit,
) {
    @Suppress("DEPRECATION")
    val smsManager: SmsManager = when {
        subscriptionId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 ->
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        else -> SmsManager.getDefault()
    }

    val sentIntent = PendingIntent.getBroadcast(
        context,
        msg.id,
        Intent(SMS_SENT_ACTION).setPackage(context.packageName).putExtra(EXTRA_MSG_ID, msg.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val deliveredIntent = PendingIntent.getBroadcast(
        context,
        msg.id + 100000,
        Intent(SMS_DELIVERED_ACTION).setPackage(context.packageName).putExtra(EXTRA_MSG_ID, msg.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // Register one-shot receivers
    val sentReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getIntExtra(EXTRA_MSG_ID, -1)
            val status = if (resultCode == android.app.Activity.RESULT_OK) SmsStatus.SENT else SmsStatus.FAILED
            onStatusChange(id, status)
            if (id == msg.id) {
                ContactCallBehaviorPreferences.addJournalEntry(
                    ctx,
                    contactId,
                    ContactSmsJournalEntry(
                        body = msg.body,
                        simLabel = msg.simLabel,
                        delivered = status == SmsStatus.SENT || status == SmsStatus.DELIVERED,
                        templateUsed = DEFAULT_SMS_TEMPLATES.firstOrNull { it == msg.body },
                    )
                )
            }
            ctx.unregisterReceiver(this)
        }
    }
    val deliveredReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getIntExtra(EXTRA_MSG_ID, -1)
            onStatusChange(id, SmsStatus.DELIVERED)
            ctx.unregisterReceiver(this)
        }
    }

    val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Context.RECEIVER_NOT_EXPORTED else 0
    context.registerReceiver(sentReceiver, IntentFilter(SMS_SENT_ACTION), flag)
    context.registerReceiver(deliveredReceiver, IntentFilter(SMS_DELIVERED_ACTION), flag)

    onStatusChange(msg.id, SmsStatus.SENDING)

    if (msg.isFlash) {
        // Class 0 PDU path
        val pdu = buildClass0Pdu(msg.to, msg.body)
        if (pdu != null) {
            trySendClass0(smsManager, msg.to, pdu, sentIntent)
        } else {
            smsManager.sendTextMessage(msg.to, null, msg.body, sentIntent, deliveredIntent)
        }
    } else {
        val parts = smsManager.divideMessage(msg.body)
        if (parts.size > 1) {
            val sentList = ArrayList<PendingIntent>(parts.size).also { it.add(sentIntent) }
            val deliveredList = ArrayList<PendingIntent>(parts.size).also { it.add(deliveredIntent) }
            smsManager.sendMultipartTextMessage(msg.to, null, parts, sentList, deliveredList)
        } else {
            smsManager.sendTextMessage(msg.to, null, msg.body, sentIntent, deliveredIntent)
        }
    }
}

@Suppress("DEPRECATION")
private fun trySendClass0(smsManager: SmsManager, to: String, pdu: ByteArray, sentIntent: PendingIntent?) {
    try {
        val m = smsManager.javaClass.getDeclaredMethod(
            "sendRawPdu", ByteArray::class.java, ByteArray::class.java,
            PendingIntent::class.java, PendingIntent::class.java, Boolean::class.java,
        )
        m.isAccessible = true
        m.invoke(smsManager, byteArrayOf(0x00), pdu, sentIntent, null, false)
    } catch (_: Exception) {
        try {
            smsManager.sendDataMessage(to, null, (-1).toShort(), pdu, sentIntent, null)
        } catch (_: Exception) {
            smsManager.sendTextMessage(to, null, String(pdu, Charsets.ISO_8859_1), sentIntent, null)
        }
    }
}

// ── UI ─────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsComposerRoute(
    onBack: () -> Unit,
    initialNumber: String = "",
    initialName: String = "",
    initialFlash: Boolean = false,
    initialContactId: String? = null,
    initialTemplate: String = "",
) {
    val context = LocalContext.current

    var toNumber by remember { mutableStateOf(initialNumber) }
    var toName by remember { mutableStateOf(initialName) }
    var body by remember { mutableStateOf(initialTemplate) }
    var isFlash by remember { mutableStateOf(initialFlash) }
    var selectedSubId by remember { mutableIntStateOf(-1) }
    var showTemplates by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf<SmsMessage>()) }

    val sims = remember { availableSmsSimOptions(context) }
    if (selectedSubId == -1 && sims.isNotEmpty()) selectedSubId = sims.first().subscriptionId

    fun send() {
        val num = toNumber.trim(); val txt = body.trim()
        if (num.isBlank() || txt.isBlank()) return
        val simLabel = sims.firstOrNull { it.subscriptionId == selectedSubId }?.label ?: "Default"
        val msg = SmsMessage(to = num, toName = toName.ifBlank { num }, body = txt, simLabel = simLabel, isFlash = isFlash)
        messages = listOf(msg) + messages
        body = ""
        sendSmsWithTracking(context, msg, selectedSubId, initialContactId) { id, status ->
            messages = messages.map { if (it.id == id) it.copy(status = status) else it }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SMS Composer", fontWeight = FontWeight.SemiBold)
                        if (toName.isNotBlank() || toNumber.isNotBlank())
                            Text(
                                toName.ifBlank { toNumber },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = { showTemplates = !showTemplates }) {
                        Icon(Icons.Default.FormatQuote, contentDescription = "Templates")
                    }
                }
            )
        },
        bottomBar = {
            Column {
                // Flash SMS toggle
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bolt, null, Modifier.size(16.dp), tint = if (isFlash) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Flash SMS (Class 0)", style = MaterialTheme.typography.labelMedium, color = if (isFlash) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = isFlash, onCheckedChange = { isFlash = it }, modifier = Modifier.scale(0.8f))
                }

                // Compose row
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = body,
                        onValueChange = { if (it.length <= 160) body = it },
                        placeholder = { Text("Message…") },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        shape = RoundedCornerShape(20.dp),
                        supportingText = { Text("${body.length}/160", style = MaterialTheme.typography.labelSmall) },
                    )
                    FloatingActionButton(
                        onClick = { send() },
                        modifier = Modifier.size(52.dp),
                        containerColor = if (body.isNotBlank() && toNumber.isNotBlank())
                            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Icon(Icons.Default.Send, "Send")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // Recipient
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = toNumber,
                    onValueChange = { toNumber = it },
                    label = { Text("To (number)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            }

            // SIM selector
            if (sims.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sims) { sim ->
                        FilterChip(
                            selected = selectedSubId == sim.subscriptionId,
                            onClick = { selectedSubId = sim.subscriptionId },
                            label = { Text(sim.label) },
                        )
                    }
                }
            }

            // Templates drawer
            AnimatedContent(targetState = showTemplates, label = "templates") { show ->
                if (show) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text("Quick Templates", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(DEFAULT_SMS_TEMPLATES) { template ->
                                SuggestionChip(
                                    onClick = { body = template; showTemplates = false },
                                    label = { Text(template, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            HorizontalDivider()

            // Message history
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                reverseLayout = true,
            ) {
                items(messages) { msg -> SmsMessageBubble(msg) }
                if (messages.isEmpty()) {
                    item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No messages yet", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmsMessageBubble(msg: SmsMessage) {
    val fmt = remember { SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()) }
    val (statusIcon, statusColor, statusLabel) = when (msg.status) {
        SmsStatus.PENDING  -> Triple(Icons.Default.Schedule,     Color(0xFF9E9E9E), "Pending")
        SmsStatus.SENDING  -> Triple(Icons.Default.Sync,         Color(0xFF2196F3), "Sending…")
        SmsStatus.SENT     -> Triple(Icons.Default.Check,        Color(0xFF4CAF50), "Sent")
        SmsStatus.DELIVERED -> Triple(Icons.Default.DoneAll,     Color(0xFF1976D2), "Delivered")
        SmsStatus.FAILED   -> Triple(Icons.Default.ErrorOutline, Color(0xFFF44336), "Failed")
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    if (msg.isFlash) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
                )
                .padding(10.dp),
        ) {
            if (msg.isFlash) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bolt, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.error)
                    Text("Flash", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }
            Text(msg.body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    fmt.format(Date(msg.sentAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(statusIcon, contentDescription = statusLabel, Modifier.size(14.dp), tint = statusColor)
                Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = statusColor, fontWeight = FontWeight.Medium)
            }
            Text("via ${msg.simLabel}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// Extension helper
private fun androidx.compose.ui.Modifier.scale(scale: Float) =
    this.graphicsLayer(scaleX = scale, scaleY = scale)
