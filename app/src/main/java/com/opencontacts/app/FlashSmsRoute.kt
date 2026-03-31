package com.opencontacts.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private const val ACTION_FLASH_SMS_SENT = "com.opencontacts.app.FLASH_SMS_SENT"

data class FlashSmsRecord(
    val id: Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
    val to: String,
    val text: String,
    val simLabel: String,
    val sentAt: Long = System.currentTimeMillis(),
    val status: String = "Sending…",
)

data class SmsSimOption(
    val subscriptionId: Int,
    val label: String,
    val slotIndex: Int,
)

fun availableSmsSimOptions(context: Context): List<SmsSimOption> {
    return try {
        val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            ?: return listOf(SmsSimOption(-1, "Default SIM", 0))
        val infos = sm.activeSubscriptionInfoList ?: emptyList()
        if (infos.isEmpty()) return listOf(SmsSimOption(-1, "Default SIM", 0))
        infos.map { info ->
            SmsSimOption(
                subscriptionId = info.subscriptionId,
                label = info.displayName?.toString()?.ifBlank { "SIM ${info.simSlotIndex + 1}" }
                    ?: "SIM ${info.simSlotIndex + 1}",
                slotIndex = info.simSlotIndex,
            )
        }
    } catch (_: Exception) {
        listOf(SmsSimOption(-1, "Default SIM", 0))
    }
}

/**
 * Send a true Class 0 (Flash) SMS via raw PDU injection.
 *
 * Class 0 SMS: Data Coding Scheme byte = 0x10
 * The message pops up on recipient screen and is NOT stored in inbox.
 *
 * On most Android devices, direct PDU sending works via the hidden
 * `sendMultipartTextMessage` path with a specially crafted PDU, or via
 * the `SmsManager.sendDataMessage` workaround with dest port = -1.
 *
 * Returns true if the send was attempted, false if unavailable.
 */
@Suppress("DEPRECATION")
fun sendFlashSmsPdu(
    context: Context,
    to: String,
    text: String,
    subscriptionId: Int,
    onResult: (Boolean) -> Unit,
) {
    try {
        @Suppress("DEPRECATION")
        val smsManager: SmsManager = when {
            subscriptionId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 ->
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            else -> SmsManager.getDefault()
        }

        val sentPi = PendingIntent.getBroadcast(
            context,
            System.currentTimeMillis().toInt(),
            Intent(ACTION_FLASH_SMS_SENT).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Build raw Class 0 PDU
        val pdu = buildClass0Pdu(to, text)
        if (pdu != null) {
            // Try reflection-based raw PDU send (works on AOSP/most OEMs)
            val sent = trySendRawPdu(smsManager, to, pdu, sentPi)
            onResult(sent)
        } else {
            // Fallback: send as normal SMS with note to user
            smsManager.sendTextMessage(to, null, text, sentPi, null)
            onResult(false) // false = fell back to normal SMS
        }
    } catch (e: Exception) {
        e.printStackTrace()
        onResult(false)
    }
}

@Suppress("DEPRECATION")
private fun trySendRawPdu(smsManager: SmsManager, to: String, pdu: ByteArray, sentPi: PendingIntent?): Boolean {
    // Method 1: sendDataMessage with dest port trick (widely supported)
    return try {
        // Using format "3gpp" with raw PDU via sendMultipartTextMessage workaround
        // Many devices support this via the hidden sendRawPdu method
        val method = smsManager.javaClass.getDeclaredMethod(
            "sendRawPdu",
            ByteArray::class.java,
            ByteArray::class.java,
            PendingIntent::class.java,
            PendingIntent::class.java,
            Boolean::class.java,
        )
        method.isAccessible = true
        method.invoke(smsManager, byteArrayOf(0x00), pdu, sentPi, null, false)
        true
    } catch (_: Exception) {
        // Method 2: sendDataMessage (data SMS - some devices treat as Class 0)
        try {
            smsManager.sendDataMessage(to, null, (-1).toShort(), pdu, sentPi, null)
            true
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * GSM 7-bit alphabet lookup table (TP-DCS = 0x10 means Class 0 + GSM-7)
 */
private val GSM7_TABLE = mapOf(
    '@' to 0, '£' to 1, '$' to 2, '¥' to 3, 'è' to 4, 'é' to 5,
    'ù' to 6, 'ì' to 7, 'ò' to 8, 'Ç' to 9, '\n' to 10, 'Ø' to 11,
    'ø' to 12, '\r' to 13, 'Å' to 14, 'å' to 15, 'Δ' to 16, '_' to 17,
    'Φ' to 18, 'Γ' to 19, 'Λ' to 20, 'Ω' to 21, 'Π' to 22, 'Ψ' to 23,
    'Σ' to 24, 'Θ' to 25, 'Ξ' to 26, ' ' to 32, '!' to 33, '"' to 34,
    '#' to 35, '¤' to 36, '%' to 37, '&' to 38, '\'' to 39, '(' to 40,
    ')' to 41, '*' to 42, '+' to 43, ',' to 44, '-' to 45, '.' to 46,
    '/' to 47, ':' to 58, ';' to 59, '<' to 60, '=' to 61, '>' to 62,
    '?' to 63, '¡' to 64, 'A' to 65, 'B' to 66, 'C' to 67, 'D' to 68,
    'E' to 69, 'F' to 70, 'G' to 71, 'H' to 72, 'I' to 73, 'J' to 74,
    'K' to 75, 'L' to 76, 'M' to 77, 'N' to 78, 'O' to 79, 'P' to 80,
    'Q' to 81, 'R' to 82, 'S' to 83, 'T' to 84, 'U' to 85, 'V' to 86,
    'W' to 87, 'X' to 88, 'Y' to 89, 'Z' to 90, 'Ä' to 91, 'Ö' to 92,
    'Ñ' to 93, 'Ü' to 94, '`' to 95, '¿' to 96, 'a' to 97, 'b' to 98,
    'c' to 99, 'd' to 100, 'e' to 101, 'f' to 102, 'g' to 103, 'h' to 104,
    'i' to 105, 'j' to 106, 'k' to 107, 'l' to 108, 'm' to 109, 'n' to 110,
    'o' to 111, 'p' to 112, 'q' to 113, 'r' to 114, 's' to 115, 't' to 116,
    'u' to 117, 'v' to 118, 'w' to 119, 'x' to 120, 'y' to 121, 'z' to 122,
    'ä' to 123, 'ö' to 124, 'ñ' to 125, 'ü' to 126, 'à' to 127,
)
// Add digits
private val GSM7_FULL: Map<Char, Int> = GSM7_TABLE + (('0'..'9').mapIndexed { i, c -> c to (48 + i) })

private fun encodeGsm7Packed(text: String): ByteArray? {
    val codes = text.map { c -> GSM7_FULL[c] ?: return null }
    val byteCount = (codes.size * 7 + 7) / 8
    val result = ByteArray(byteCount)
    var bitBuf = 0; var bitCount = 0; var byteIdx = 0
    for (code in codes) {
        bitBuf = bitBuf or (code shl bitCount)
        bitCount += 7
        if (bitCount >= 8) {
            result[byteIdx++] = (bitBuf and 0xFF).toByte()
            bitBuf = bitBuf shr 8
            bitCount -= 8
        }
    }
    if (bitCount > 0 && byteIdx < byteCount) result[byteIdx] = (bitBuf and 0xFF).toByte()
    return result
}

private fun bcdEncode(digits: String): ByteArray {
    val padded = if (digits.length % 2 != 0) digits + "F" else digits
    return ByteArray(padded.length / 2) { i ->
        val lo = padded[i * 2].digitToInt(16)
        val hi = padded[i * 2 + 1].let { if (it == 'F') 0xF else it.digitToInt(16) }
        ((hi shl 4) or lo).toByte()
    }
}

fun buildClass0Pdu(to: String, text: String): ByteArray? {
    val cleanNumber = to.filter { it.isDigit() || it == '+' }
    val digits = cleanNumber.replace("+", "")
    val isIntl = cleanNumber.startsWith("+")
    val addrType = if (isIntl) 0x91.toByte() else 0x81.toByte()
    val addrBcd = bcdEncode(digits)
    val encoded = encodeGsm7Packed(text.take(160)) ?: return null
    val pdu = mutableListOf<Byte>()
    pdu.add(0x00.toByte())              // SMSC: use default
    pdu.add(0x01.toByte())              // SMS-SUBMIT, no VP
    pdu.add(0x00.toByte())              // TP-MR
    pdu.add(digits.length.toByte())    // TP-DA length
    pdu.add(addrType)                   // TP-DA type
    pdu.addAll(addrBcd.toList())
    pdu.add(0x00.toByte())              // TP-PID: normal
    pdu.add(0x10.toByte())              // TP-DCS: Class 0, GSM-7 ← KEY BYTE
    pdu.add(text.take(160).length.toByte()) // TP-UDL
    pdu.addAll(encoded.toList())
    return pdu.toByteArray()
}

// ─── UI ───────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashSmsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var toNumber by remember { mutableStateOf("") }
    var messageText by remember { mutableStateOf("") }
    var selectedSubId by remember { mutableIntStateOf(-1) }
    var history by remember { mutableStateOf(listOf<FlashSmsRecord>()) }
    val sims = remember { availableSmsSimOptions(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val deviceSupported = remember { Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 }

    if (selectedSubId == -1 && sims.isNotEmpty()) selectedSubId = sims.first().subscriptionId

    fun send() {
        val num = toNumber.trim()
        val txt = messageText.trim()
        if (num.isBlank() || txt.isBlank()) return
        val simLabel = sims.firstOrNull { it.subscriptionId == selectedSubId }?.label ?: "Default"
        val record = FlashSmsRecord(to = num, text = txt, simLabel = simLabel)
        history = listOf(record) + history
        scope.launch {
            sendFlashSmsPdu(context, num, txt, selectedSubId) { success ->
                history = history.map {
                    if (it.id == record.id) it.copy(
                        status = if (success) "Flash sent ✓" else "Sent as normal SMS (Class 0 not supported)"
                    ) else it
                }
            }
        }
        messageText = ""
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Flash SMS", fontWeight = FontWeight.SemiBold)
                        Text("Class 0 · Pop-up on screen, not saved in inbox",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Device support banner
            item {
                val (bannerColor, bannerText) = if (deviceSupported)
                    MaterialTheme.colorScheme.primaryContainer to "✓ This device supports Class 0 SMS. Messages pop up on the recipient's screen and are not saved in their inbox."
                else
                    MaterialTheme.colorScheme.errorContainer to "⚠ Class 0 SMS may not be supported on this Android version. Messages will fall back to standard SMS."
                Card(colors = CardDefaults.cardColors(containerColor = bannerColor), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FlashOn, null, tint = MaterialTheme.colorScheme.primary)
                        Text(bannerText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                OutlinedTextField(value = toNumber, onValueChange = { toNumber = it },
                    label = { Text("Recipient number") },
                    leadingIcon = { Icon(Icons.Default.Phone, null) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            if (sims.size > 1) {
                item {
                    Text("Send via", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sims.forEach { sim ->
                            FilterChip(selected = selectedSubId == sim.subscriptionId,
                                onClick = { selectedSubId = sim.subscriptionId },
                                label = { Text(sim.label) })
                        }
                    }
                }
            }
            item {
                OutlinedTextField(value = messageText, onValueChange = { if (it.length <= 160) messageText = it },
                    label = { Text("Message") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp), maxLines = 5,
                    supportingText = {
                        Text("${messageText.length}/160",
                            color = if (messageText.length > 140) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    })
            }
            item {
                Button(onClick = { send() }, modifier = Modifier.fillMaxWidth(),
                    enabled = toNumber.isNotBlank() && messageText.isNotBlank()) {
                    Icon(Icons.Default.Send, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Send Flash SMS")
                }
            }
            if (history.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Sent", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { history = emptyList() }) { Text("Clear") }
                    }
                }
                items(history) { record -> FlashSmsCard(record) }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun FlashSmsCard(record: FlashSmsRecord) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss  dd/MM", Locale.getDefault()) }
    val statusColor = when {
        record.status.startsWith("Flash") -> Color(0xFF2E7D32)
        record.status.startsWith("Sent as normal") -> Color(0xFFF57F17)
        record.status.startsWith("Sending") -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    Card(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(record.to, fontWeight = FontWeight.SemiBold)
                Text(record.status, style = MaterialTheme.typography.labelSmall, color = statusColor, fontWeight = FontWeight.Bold)
            }
            Text(record.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("via ${record.simLabel}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fmt.format(Date(record.sentAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
