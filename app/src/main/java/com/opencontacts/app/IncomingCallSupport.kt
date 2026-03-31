package com.opencontacts.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhoneMissed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import com.opencontacts.core.crypto.AppLockRepository
import com.opencontacts.core.crypto.AppLockSettings
import com.opencontacts.core.model.ContactSummary
import com.opencontacts.core.vault.VaultSessionManager
import com.opencontacts.core.common.PhoneNumberNormalizer
import com.opencontacts.domain.contacts.ContactRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip

private const val CALLS_CHANNEL_ID = "incoming_calls"
private const val MISSED_CHANNEL_ID = "missed_calls"
private const val ONGOING_CALL_CHANNEL_ID = "ongoing_call"
private const val INCOMING_NOTIFICATION_ID = 33001
private const val MISSED_NOTIFICATION_ID = 33002
private const val ONGOING_CALL_NOTIFICATION_ID = 33003
private const val ACTION_ANSWER_CALL = "com.opencontacts.app.action.ANSWER_CALL"
private const val ACTION_DECLINE_CALL = "com.opencontacts.app.action.DECLINE_CALL"
private const val ACTION_DISMISS_CALL = "com.opencontacts.app.action.DISMISS_CALL"
private const val ACTION_CALL_BACK = "com.opencontacts.app.action.CALL_BACK"
private const val ACTION_OPEN_CONTACT = "com.opencontacts.app.action.OPEN_CONTACT"
private const val ACTION_OPEN_ACTIVE_CALL = "com.opencontacts.app.action.OPEN_ACTIVE_CALL"
private const val ACTION_END_ACTIVE_CALL = "com.opencontacts.app.action.END_ACTIVE_CALL"
private const val ACTION_TOGGLE_SPEAKER = "com.opencontacts.app.action.TOGGLE_SPEAKER"
private const val ACTION_TOGGLE_MUTE = "com.opencontacts.app.action.TOGGLE_MUTE"
private const val ACTION_QUICK_SMS = "com.opencontacts.app.action.QUICK_SMS"
private const val ACTION_REMIND_LATER = "com.opencontacts.app.action.REMIND_LATER"
private const val ACTION_ADD_CONTACT = "com.opencontacts.app.action.ADD_CONTACT"
private const val ACTION_ADD_TO_FOLDER_VAULT = "com.opencontacts.app.action.ADD_TO_FOLDER_VAULT"

private val incomingCallScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

data class IncomingCallUiState(
    val displayName: String,
    val number: String,
    val photoUri: String? = null,
    val folderName: String? = null,
    val tags: List<String> = emptyList(),
    val contactId: String? = null,
    val blockMode: String = "NONE",
)

object IncomingCallOverlayController {
    val state = MutableStateFlow<IncomingCallUiState?>(null)

    fun show(call: IncomingCallUiState) {
        state.value = call
    }

    fun clear() {
        state.value = null
    }
}

object IncomingCallTracker {
    var lastState: String? = null
    var lastRinging: IncomingCallUiState? = null
    var answered: Boolean = false
    var ringStartedAtMillis: Long? = null
}

data class MissedCallSmartMeta(
    val ringDurationSeconds: Int,
    val repeatedWithinHour: Int,
    val unknownRepeated: Boolean,
    val shouldAutoRemind: Boolean,
    val importantCaller: Boolean,
)

@EntryPoint
@InstallIn(SingletonComponent::class)
interface IncomingCallEntryPoint {
    fun contactRepository(): ContactRepository
    fun appLockRepository(): AppLockRepository
    fun vaultSessionManager(): VaultSessionManager
    fun transferDestinationManager(): com.opencontacts.data.repository.TransferDestinationManager
}

class IncomingCallBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val pendingResult = goAsync()
        incomingCallScope.launch {
            runCatching {
                handlePhoneStateChanged(context, intent)
            }
            pendingResult.finish()
        }
    }
}

class IncomingCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANSWER_CALL -> answerIncomingCall(context)
            ACTION_DECLINE_CALL -> declineIncomingCall(context)
            ACTION_DISMISS_CALL -> dismissIncomingUi(context)
            ACTION_CALL_BACK -> {
                val number = intent.getStringExtra("number").orEmpty()
                if (number.isNotBlank()) {
                    context.startActivity(
                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                dismissIncomingUi(context)
            }
            ACTION_QUICK_SMS -> {
                val number = intent.getStringExtra("number").orEmpty()
                if (number.isNotBlank()) {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra("navigate_to", "settings/smscomposer")
                            .putExtra("sms_to", number),
                    )
                }
            }
            ACTION_REMIND_LATER -> {
                val number = intent.getStringExtra("number").orEmpty()
                val name = intent.getStringExtra("name").orEmpty()
                val delayMinutes = intent.getIntExtra("delay_minutes", 10)
                scheduleCallReminder(context, number, name, delayMinutes)
            }
            ACTION_OPEN_CONTACT -> {
                val contactId = intent.getStringExtra("contactId").orEmpty()
                val mainIntent = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (contactId.isNotBlank()) {
                    mainIntent.putExtra(EXTRA_OPEN_CONTACT_ID, contactId)
                }
                context.startActivity(mainIntent)
                dismissIncomingUi(context)
            }
            ACTION_ADD_CONTACT -> {
                val number = intent.getStringExtra("number").orEmpty()
                val name = intent.getStringExtra("name").orEmpty()
                if (number.isNotBlank()) {
                    val addIntent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                        type = ContactsContract.RawContacts.CONTENT_TYPE
                        putExtra(ContactsContract.Intents.Insert.PHONE, number)
                        putExtra(ContactsContract.Intents.Insert.NAME, name)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(addIntent) }
                }
            }
            ACTION_ADD_TO_FOLDER_VAULT -> {
                val number = intent.getStringExtra("number").orEmpty()
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("create_contact_number", number)
                )
            }
            ACTION_OPEN_ACTIVE_CALL -> {
                ActiveCallOverlayController.state.value?.let { launchActiveCallControls(context, it, forceShow = true) }
                dismissFloatingIncomingCall(context)
            }
            ACTION_END_ACTIVE_CALL -> {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                        telecomManager.endCall()
                    }
                }
                ActiveCallOverlayController.clear()
                cancelOngoingCallNotification(context)
            }
            ACTION_TOGGLE_SPEAKER -> {
                TelecomCallCoordinator.toggleSpeaker()
                // Refresh notification to reflect new state
                ActiveCallOverlayController.state.value?.let { postOngoingCallNotification(context, it) }
            }
            ACTION_TOGGLE_MUTE -> {
                val currentMuted = TelecomCallCoordinator.telecomState.value.isMuted
                TelecomCallCoordinator.setMuted(!currentMuted)
                ActiveCallOverlayController.state.value?.let { postOngoingCallNotification(context, it) }
            }
        }
    }
}

private suspend fun handlePhoneStateChanged(context: Context, intent: Intent) {
    if (isDefaultDialerPackage(context)) return
    val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE).orEmpty()
    val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
    val matched = lookupCurrentContact(context, number)
    val settings = entryPoint(context).appLockRepository().settings.first()
    val uiState = matched.toIncomingCallUiState(number)

    when (state) {
        TelephonyManager.EXTRA_STATE_RINGING -> {
            IncomingCallTracker.lastState = state
            IncomingCallTracker.lastRinging = uiState
            IncomingCallTracker.answered = false
            IncomingCallTracker.ringStartedAtMillis = System.currentTimeMillis()
            // Flash alert on incoming call
            if (settings.flashAlertOnIncomingCall) {
                FlashAlertManager.startFlashing(context)
            }
            if (settings.enableIncomingCallerPopup) {
                presentIncomingCallExperience(context, uiState)
            } else {
                postIncomingCallNotification(context, uiState, settings)
            }
        }
        TelephonyManager.EXTRA_STATE_OFFHOOK -> {
            IncomingCallTracker.lastState = state
            IncomingCallTracker.answered = true
            FlashAlertManager.stopFlashing(context)
            // Auto-end call after configured duration
            if (settings.callTimerEnabled && settings.callTimerDurationSeconds > 0) {
                CallTimerManager.start(context, settings.callTimerDurationSeconds)
            }
            IncomingCallTracker.lastRinging?.let { ringing ->
                launchActiveCallControls(context, ringing.toActiveCallUiState(), forceShow = AppVisibilityTracker.isForeground.value)
            }
            dismissIncomingUi(context)
        }
        TelephonyManager.EXTRA_STATE_IDLE -> {
            val wasRinging = IncomingCallTracker.lastState == TelephonyManager.EXTRA_STATE_RINGING || IncomingCallTracker.lastRinging != null
            val ringingState = IncomingCallTracker.lastRinging
            FlashAlertManager.stopFlashing(context)
            CallTimerManager.cancel()
            MiniCallFloatingService.hide(context)
            dismissIncomingUi(context)
            ActiveCallOverlayController.clear()
            cancelOngoingCallNotification(context)
            if (wasRinging && !IncomingCallTracker.answered && settings.enableMissedCallNotification && ringingState != null) {
                val ringDurationSeconds = (((System.currentTimeMillis() - (IncomingCallTracker.ringStartedAtMillis ?: System.currentTimeMillis())).coerceAtLeast(0L)) / 1000L).toInt()
                postMissedCallNotification(context, ringingState, settings, ringDurationSeconds)
            }
            IncomingCallTracker.lastState = state
            IncomingCallTracker.lastRinging = null
            IncomingCallTracker.answered = false
            IncomingCallTracker.ringStartedAtMillis = null
        }
    }
}

private suspend fun lookupCurrentContact(context: Context, incomingNumber: String): ContactSummary? {
    val entryPoint = entryPoint(context)
    val vaultId = entryPoint.vaultSessionManager().activeVaultId.value ?: return null
    val contacts = entryPoint.contactRepository().observeContacts(vaultId).first()
    val normalized = normalizeIncomingNumber(incomingNumber)
    return contacts.firstOrNull { normalizeIncomingNumber(it.primaryPhone) == normalized }
}

internal fun ContactSummary?.toIncomingCallUiState(rawNumber: String): IncomingCallUiState {
    return IncomingCallUiState(
        displayName = this?.displayName ?: if (rawNumber.isBlank()) "Unknown caller" else rawNumber,
        number = rawNumber.ifBlank { this?.primaryPhone.orEmpty() },
        photoUri = this?.photoUri,
        folderName = this?.folderName,
        tags = this?.tags.orEmpty(),
        contactId = this?.id,
        blockMode = this?.blockMode ?: "NONE",
    )
}

internal fun postIncomingCallNotification(context: Context, call: IncomingCallUiState, settings: AppLockSettings) {
    ensureChannels(context, settings)
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return

    val contentIntent = PendingIntent.getActivity(
        context,
        6001,
        Intent(context, IncomingCallOverlayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
    )

    val builder = NotificationCompat.Builder(context, CALLS_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.sym_call_incoming)
        .setContentTitle(privacyTitle(call, settings))
        .setContentText(privacyText(call, settings, "Incoming call"))
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setPriority(if (settings.headsUpNotifications) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
        .setOngoing(true)
        .setAutoCancel(false)
        .setVisibility(lockScreenVisibility(settings))
        .setContentIntent(contentIntent)
        .setFullScreenIntent(contentIntent, settings.overlayPopupMode != "IN_APP_ONLY")
        .setOnlyAlertOnce(call.blockMode.equals("SILENT_RING", ignoreCase = true))
        .setSilent(call.blockMode.equals("SILENT_RING", ignoreCase = true))
        .addAction(0, "Answer", broadcastPendingIntent(context, ACTION_ANSWER_CALL))
        .addAction(0, "Decline", broadcastPendingIntent(context, ACTION_DECLINE_CALL))
        .addAction(0, "Dismiss", broadcastPendingIntent(context, ACTION_DISMISS_CALL))
    loadNotificationBitmap(context, settings, call.photoUri)?.let(builder::setLargeIcon)
    if (settings.showFolderTagsInNotifications && !settings.lockScreenNotificationVisibility.equals("SHOW_NUMBER_ONLY", ignoreCase = true)) {
        val extras = buildList {
            call.folderName?.takeIf(String::isNotBlank)?.let { add(it) }
            addAll(call.tags.take(2))
        }
        if (extras.isNotEmpty()) builder.setSubText(extras.joinToString(" • "))
    }
    manager.notify(INCOMING_NOTIFICATION_ID, builder.build())
}

internal fun postMissedCallNotification(context: Context, call: IncomingCallUiState, settings: AppLockSettings, ringDurationSeconds: Int) {
    ensureChannels(context, settings)
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return

    val smartMeta = registerMissedCallAndAnalyze(context, call, ringDurationSeconds)
    val builder = NotificationCompat.Builder(context, MISSED_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.sym_call_missed)
        .setContentTitle(if (smartMeta.importantCaller) "Important missed call" else "Missed call")
        .setContentText(
            buildString {
                append(call.displayName)
                if (call.number.isNotBlank()) append(" • ${call.number}")
            },
        )
        .setStyle(
            NotificationCompat.BigTextStyle().bigText(
                buildString {
                    append(call.displayName)
                    if (call.number.isNotBlank()) append(" • ${call.number}")
                    append("\nTap an action below.")
                    if (smartMeta.ringDurationSeconds in 1..4) append("\nShort ring (<5s) — callback reminder prepared.")
                    if (smartMeta.repeatedWithinHour >= 3) append("\nRepeated caller (${smartMeta.repeatedWithinHour} times in the last hour).")
                    if (smartMeta.unknownRepeated) append("\nUnknown number repeated more than once — consider saving it.")
                }
            )
        )
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setVisibility(lockScreenVisibility(settings))
        .setAutoCancel(true)
        .addAction(0, "Call now", broadcastPendingIntent(context, ACTION_CALL_BACK, call.number))
        .addAction(0, "Send SMS", broadcastPendingIntent(context, ACTION_QUICK_SMS, call.number))
        .addAction(0, "Remind 10m", PendingIntent.getBroadcast(
            context, 9901,
            Intent(context, IncomingCallActionReceiver::class.java).apply {
                action = ACTION_REMIND_LATER
                putExtra("number", call.number)
                putExtra("name", call.displayName)
                putExtra("delay_minutes", 10)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        ))
        .addAction(0, "Add contact", broadcastPendingIntent(context, ACTION_ADD_CONTACT, call.number, call.displayName))
        .addAction(0, "Add to folder/vault", broadcastPendingIntent(context, ACTION_ADD_TO_FOLDER_VAULT, call.number, call.displayName))
        .addAction(0, "Open contact", broadcastPendingIntent(context, ACTION_OPEN_CONTACT, call.contactId.orEmpty()))

    loadNotificationBitmap(context, settings, call.photoUri)?.let(builder::setLargeIcon)
    val extras = buildList {
        call.folderName?.takeIf(String::isNotBlank)?.let { add(it) }
        addAll(call.tags.take(2))
        if (smartMeta.importantCaller) add("Important")
    }
    if (extras.isNotEmpty()) builder.setSubText(extras.joinToString(" • "))

    if (smartMeta.shouldAutoRemind) {
        scheduleCallReminder(context, call.number, call.displayName, 10)
    }
    manager.notify(MISSED_NOTIFICATION_ID, builder.build())
    showMissedCallQuickActions(context, call, smartMeta)
}

internal fun postOngoingCallNotification(context: Context, call: ActiveCallUiState) {
    val settings = runCatching { runBlocking { entryPoint(context).appLockRepository().settings.first() } }.getOrDefault(AppLockSettings.DEFAULT)
    ensureChannels(context, settings)
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return
    val openIntent = PendingIntent.getBroadcast(
        context,
        7001,
        Intent(context, IncomingCallActionReceiver::class.java).setAction(ACTION_OPEN_ACTIVE_CALL),
        PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
    )
    val endIntent = PendingIntent.getBroadcast(
        context,
        7002,
        Intent(context, IncomingCallActionReceiver::class.java).setAction(ACTION_END_ACTIVE_CALL),
        PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
    )
    val speakerIntent = PendingIntent.getBroadcast(
        context, 7003,
        Intent(context, IncomingCallActionReceiver::class.java).setAction(ACTION_TOGGLE_SPEAKER),
        PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
    )
    val muteIntent = PendingIntent.getBroadcast(
        context, 7004,
        Intent(context, IncomingCallActionReceiver::class.java).setAction(ACTION_TOGGLE_MUTE),
        PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
    )
    val audioState = TelecomCallCoordinator.telecomState.value
    val isSpeaker = audioState.currentAudioRoute == android.telecom.CallAudioState.ROUTE_SPEAKER
    val isMuted = audioState.isMuted
    val speakerLabel = if (isSpeaker) "🔊 Speaker On" else "🔈 Speaker Off"
    val muteLabel = if (isMuted) "🎙️ Unmute" else "🔇 Mute"
    val builder = NotificationCompat.Builder(context, ONGOING_CALL_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.sym_call_outgoing)
        .setContentTitle(privacyTitle(call, settings))
        .setContentText(privacyText(call, settings, "Call in progress"))
        .setOngoing(true)
        .setUsesChronometer(true)
        .setWhen(System.currentTimeMillis())
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setContentIntent(openIntent)
        .addAction(0, "Open", openIntent)
        .addAction(0, "End", endIntent)
        .addAction(0, speakerLabel, speakerIntent)
        .addAction(0, muteLabel, muteIntent)
        .setVisibility(lockScreenVisibility(settings))
    manager.notify(ONGOING_CALL_NOTIFICATION_ID, builder.build())
}

internal fun cancelOngoingCallNotification(context: Context) {
    NotificationManagerCompat.from(context).cancel(ONGOING_CALL_NOTIFICATION_ID)
}

internal fun dismissIncomingUi(context: Context) {
    IncomingCallOverlayController.clear()
    dismissFloatingIncomingCall(context)
    NotificationManagerCompat.from(context).cancel(INCOMING_NOTIFICATION_ID)
}

private fun answerIncomingCall(context: Context) {
    val ringing = IncomingCallTracker.lastRinging ?: IncomingCallOverlayController.state.value
    try {
        TelecomCallCoordinator.answer()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecomManager.acceptRingingCall()
        }
    } catch (_: Throwable) {
    } finally {
        dismissIncomingUi(context)
        ringing?.let { launchActiveCallControls(context, it.toActiveCallUiState(), forceShow = true) }
    }
}

private fun declineIncomingCall(context: Context) {
    try {
        TelecomCallCoordinator.decline()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecomManager.endCall()
        }
    } catch (_: Throwable) {
    } finally {
        dismissIncomingUi(context)
    }
}

private fun broadcastPendingIntent(context: Context, action: String, payload: String? = null, name: String? = null): PendingIntent {
    val intent = Intent(context, IncomingCallActionReceiver::class.java).setAction(action)
    payload?.let {
        if (action == ACTION_CALL_BACK || action == ACTION_QUICK_SMS || action == ACTION_ADD_CONTACT || action == ACTION_ADD_TO_FOLDER_VAULT) intent.putExtra("number", it)
        if (action == ACTION_OPEN_CONTACT) intent.putExtra("contactId", it)
    }
    name?.let { intent.putExtra("name", it) }
    return PendingIntent.getBroadcast(
        context,
        action.hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
    )
}

private fun immutableFlag(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0


private fun showMissedCallQuickActions(context: Context, call: IncomingCallUiState, smartMeta: MissedCallSmartMeta) {
    runCatching {
        context.startActivity(
            Intent(context, MissedCallQuickActionsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("number", call.number)
                .putExtra("name", call.displayName)
                .putExtra("contactId", call.contactId)
                .putExtra("important", smartMeta.importantCaller)
                .putExtra("repeatedWithinHour", smartMeta.repeatedWithinHour)
                .putExtra("unknownRepeated", smartMeta.unknownRepeated)
                .putExtra("shouldAutoRemind", smartMeta.shouldAutoRemind)
        )
    }
}

private fun registerMissedCallAndAnalyze(context: Context, call: IncomingCallUiState, ringDurationSeconds: Int): MissedCallSmartMeta {
    val prefs = context.getSharedPreferences("opencontacts_missed_call_rules", Context.MODE_PRIVATE)
    val key = normalizeIncomingNumber(call.number)
    val now = System.currentTimeMillis()
    val historyRaw = prefs.getString("history_$key", "").orEmpty()
    val recent = historyRaw.split(',').mapNotNull { it.toLongOrNull() }.filter { now - it <= 60 * 60 * 1000L }.toMutableList()
    recent += now
    prefs.edit().putString("history_$key", recent.joinToString(",")).apply()
    val repeatedWithinHour = recent.size
    val unknownRepeated = call.contactId.isNullOrBlank() && repeatedWithinHour >= 2
    val shouldAutoRemind = ringDurationSeconds in 1..4
    val importantCaller = repeatedWithinHour >= 3
    return MissedCallSmartMeta(
        ringDurationSeconds = ringDurationSeconds,
        repeatedWithinHour = repeatedWithinHour,
        unknownRepeated = unknownRepeated,
        shouldAutoRemind = shouldAutoRemind,
        importantCaller = importantCaller,
    )
}

internal fun ensureChannels(context: Context, settings: AppLockSettings) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val callsChannel = NotificationChannel(CALLS_CHANNEL_ID, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "Incoming caller alerts and heads-up notifications"
        lockscreenVisibility = lockScreenVisibility(settings)
        enableVibration(settings.vibrationEnabled)
        if (!settings.soundEnabled) setSound(null, null)
    }
    val missedChannel = NotificationChannel(MISSED_CHANNEL_ID, "Missed calls", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "Missed call notifications"
        lockscreenVisibility = lockScreenVisibility(settings)
        enableVibration(settings.vibrationEnabled)
    }
    val ongoingChannel = NotificationChannel(ONGOING_CALL_CHANNEL_ID, "Ongoing call", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Tap to return to the current call screen."
        lockscreenVisibility = lockScreenVisibility(settings)
        setSound(null, null)
        enableVibration(false)
    }
    manager.createNotificationChannel(callsChannel)
    manager.createNotificationChannel(missedChannel)
    manager.createNotificationChannel(ongoingChannel)
}

internal fun isDefaultDialerPackage(context: Context): Boolean {
    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    return telecomManager?.defaultDialerPackage == context.packageName
}

private fun lockScreenVisibility(settings: AppLockSettings): Int {
    return if (settings.lockScreenNotificationVisibility.equals("SHOW_FULL", ignoreCase = true)) {
        NotificationCompat.VISIBILITY_PUBLIC
    } else {
        NotificationCompat.VISIBILITY_PRIVATE
    }
}


private fun privacyTitle(call: ActiveCallUiState, settings: AppLockSettings): String = when (settings.lockScreenNotificationVisibility.uppercase()) {
    "SHOW_NAME_ONLY" -> call.displayName
    "SHOW_NUMBER_ONLY" -> call.number
    "HIDE_SENSITIVE" -> "Incoming call"
    else -> call.displayName
}

private fun privacyText(call: ActiveCallUiState, settings: AppLockSettings, fallback: String): String = when (settings.lockScreenNotificationVisibility.uppercase()) {
    "SHOW_NAME_ONLY" -> fallback
    "SHOW_NUMBER_ONLY" -> fallback
    "HIDE_SENSITIVE" -> fallback
    else -> if (call.number.isNotBlank() && call.number != call.displayName) call.number else fallback
}

private fun privacyTitle(call: IncomingCallUiState, settings: AppLockSettings): String = when (settings.lockScreenNotificationVisibility.uppercase()) {
    "SHOW_NUMBER_ONLY" -> call.number.ifBlank { "Incoming call" }
    else -> call.displayName
}

private fun privacyText(call: IncomingCallUiState, settings: AppLockSettings, fallback: String): String = when (settings.lockScreenNotificationVisibility.uppercase()) {
    "SHOW_NAME_ONLY" -> fallback
    "SHOW_NUMBER_ONLY" -> fallback
    else -> call.number.ifBlank { fallback }
}

private fun loadNotificationBitmap(context: Context, settings: AppLockSettings, photoUri: String?): Bitmap? {
    if (!settings.showPhotoInNotifications || photoUri.isNullOrBlank()) return null
    return runCatching {
        context.contentResolver.openInputStream(photoUri.toUri())?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}

internal fun normalizeIncomingNumber(number: String?): String = PhoneNumberNormalizer.normalize(number)

internal fun entryPoint(context: Context): IncomingCallEntryPoint {
    return EntryPointAccessors.fromApplication(context.applicationContext, IncomingCallEntryPoint::class.java)
}

private fun shouldLaunchIncomingOverlayActivity(context: Context, settings: AppLockSettings): Boolean {
    if (AppVisibilityTracker.isForeground.value) return false
    if (!settings.enableIncomingCallerPopup) return false
    return settings.overlayPopupMode.equals("OVERLAY_WINDOW", ignoreCase = true) ||
        (settings.overlayPopupMode.equals("HEADS_UP", ignoreCase = true) && canPresentCallUiOutsideApp(context))
}

@Composable
fun IncomingCallInAppHost() {
    val call by IncomingCallOverlayController.state.collectAsStateWithLifecycle()
    val isForeground by AppVisibilityTracker.isForeground.collectAsStateWithLifecycle()
    if (call != null && isForeground) {
        IncomingCallDialog(call = call!!)
    }
}

@Composable
fun IncomingCallOverlayActivityScreen(onDismiss: () -> Unit) {
    val call by IncomingCallOverlayController.state.collectAsStateWithLifecycle()
    if (call == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    IncomingCallOverlayScreen(call = call!!, onDismiss = onDismiss)
}

@Composable
private fun IncomingCallDialog(call: IncomingCallUiState) {
    val context = LocalContext.current
    val settings by entryPoint(context).appLockRepository().settings.collectAsStateWithLifecycle(initialValue = AppLockSettings.DEFAULT)
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { dismissIncomingUi(context) },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 24.dp),
            contentAlignment = incomingCallWindowAlignment(settings.incomingCallWindowPosition),
        ) {
            IncomingCallCardContainer(call = call, modifier = incomingCallCardModifier(settings))
        }
    }
}

@Composable
private fun IncomingCallOverlayScreen(call: IncomingCallUiState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings by entryPoint(context).appLockRepository().settings.collectAsStateWithLifecycle(initialValue = AppLockSettings.DEFAULT)

    // Full-screen lock screen incoming call UI with blurred contact photo background
    val avatarBitmap = remember(call.photoUri) {
        runCatching {
            call.photoUri?.let { uri ->
                context.contentResolver.openInputStream(uri.toUri())?.use { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Background layer: contact photo full-screen ──────────────────────
        if (avatarBitmap != null) {
            Image(
                bitmap = avatarBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // Dark overlay for readability
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        } else {
            // Fallback: gradient background
            Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color(0xFF1A237E), Color(0xFF000000))
                    )
                )
            )
        }

        // ── Main content ────────────────────────────────────────────────────
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.15f))

            // Avatar circle
            if (avatarBitmap != null) {
                Image(
                    bitmap = avatarBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(110.dp).clip(CircleShape).border(3.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                )
            } else {
                Box(
                    Modifier.size(110.dp).background(Color.White.copy(alpha = 0.15f), CircleShape).border(3.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        call.displayName.take(1).uppercase(),
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Incoming call", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.75f))
            Spacer(Modifier.height(6.dp))
            Text(call.displayName, style = MaterialTheme.typography.displaySmall, color = Color.White, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
            if (call.number.isNotBlank() && call.number != call.displayName) {
                Spacer(Modifier.height(4.dp))
                Text(call.number, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f))
            }
            if (call.folderName != null) {
                Spacer(Modifier.height(4.dp))
                Surface(color = Color.White.copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp)) {
                    Text(call.folderName!!, style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }

            Spacer(Modifier.weight(1f))

            // ── Quick SMS templates ────────────────────────────────────────
            val quickReplies = listOf("I'll call you back", "I'm busy now", "On my way")
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 24.dp),
            ) {
                items(quickReplies) { msg ->
                    Surface(
                        onClick = {
                            sendQuickSmsReply(context, call.number, msg)
                            dismissIncomingUi(context)
                            onDismiss()
                        },
                        color = Color.White.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(msg, style = MaterialTheme.typography.labelMedium, color = Color.White, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Answer / Decline buttons ───────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Decline
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FloatingActionButton(
                        onClick = { declineIncomingCallPublic(context); onDismiss() },
                        containerColor = Color(0xFFF44336),
                        modifier = Modifier.size(72.dp),
                    ) {
                        Icon(Icons.Default.CallEnd, null, Modifier.size(32.dp), tint = Color.White)
                    }
                    Text("Decline", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
                }

                // Answer
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FloatingActionButton(
                        onClick = { answerIncomingCallPublic(context); onDismiss() },
                        containerColor = Color(0xFF4CAF50),
                        modifier = Modifier.size(72.dp),
                    ) {
                        Icon(Icons.Default.Call, null, Modifier.size(32.dp), tint = Color.White)
                    }
                    Text("Answer", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

private fun sendQuickSmsReply(context: Context, number: String, message: String) {
    if (number.isBlank()) return
    runCatching {
        @Suppress("DEPRECATION")
        val sms = android.telephony.SmsManager.getDefault()
        sms.sendTextMessage(number, null, message, null, null)
    }
}

private fun answerIncomingCallPublic(context: Context) {
    TelecomCallCoordinator.answer()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
        runCatching {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.acceptRingingCall()
        }
    }
    dismissIncomingUi(context)
}

private fun declineIncomingCallPublic(context: Context) {
    runCatching {
        TelecomCallCoordinator.rejectCall()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            @Suppress("DEPRECATION") tm.endCall()
        }
    }
    dismissIncomingUi(context)
}


@Composable
private fun rememberTransferCandidates(context: Context): List<ContactSummary> {
    val entryPoint = remember(context) { entryPoint(context) }
    val vaultId = entryPoint.vaultSessionManager().activeVaultId.value
    if (vaultId.isNullOrBlank()) return emptyList()
    var contacts by remember(vaultId) { mutableStateOf(emptyList<ContactSummary>()) }
    LaunchedEffect(vaultId) {
        entryPoint.contactRepository().observeContacts(vaultId).collectLatest { all ->
            contacts = all.filter { it.deletedAt == null }
        }
    }
    return contacts
}


private fun filteredTransferCandidates(contacts: List<ContactSummary>, query: String, currentNumber: String): List<ContactSummary> {
    val normalizedQuery = query.trim().lowercase()
    val currentDigits = currentNumber.filter(Char::isDigit)
    return contacts.asSequence()
        .filter { contact ->
            val numberMatchesCurrent = contact.phoneNumbers.any { phone ->
                phone.value.filter(Char::isDigit) == currentDigits && currentDigits.isNotBlank()
            } || (contact.primaryPhone?.filter(Char::isDigit) == currentDigits && currentDigits.isNotBlank())
            !numberMatchesCurrent
        }
        .filter { contact ->
            if (normalizedQuery.isBlank()) return@filter true
            val numbers = buildList {
                contact.primaryPhone?.let(::add)
                contact.phoneNumbers.forEach { add(it.value) }
            }
            contact.displayName.lowercase().contains(normalizedQuery) || numbers.any { it.lowercase().contains(normalizedQuery) }
        }
        .sortedWith(compareBy<ContactSummary> { it.displayName.lowercase() })
        .take(40)
        .toList()
}

private fun performIncomingCallTransfer(context: Context, targetNumber: String): Boolean {
    val trimmed = targetNumber.trim()
    if (trimmed.isBlank()) return false
    return TelecomCallCoordinator.deflect(trimmed).onFailure {
        android.widget.Toast.makeText(context, "Call transfer is not supported on this device or network.", android.widget.Toast.LENGTH_LONG).show()
    }.isSuccess
}

@Composable
private fun IncomingCallTransferDialog(
    call: IncomingCallUiState,
    contacts: List<ContactSummary>,
    onDismiss: () -> Unit,
    onTransferred: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val filtered = remember(contacts, query, call.number) { filteredTransferCandidates(contacts, query, call.number) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transfer incoming call") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search contacts or numbers") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                )
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (filtered.isEmpty()) {
                        Text("No matching contacts found.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        filtered.forEach { contact ->
                            val primaryNumber = contact.primaryPhone?.takeIf { it.isNotBlank() }
                                ?: contact.phoneNumbers.firstOrNull()?.value.orEmpty()
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (performIncomingCallTransfer(context, primaryNumber)) {
                                            onTransferred()
                                        }
                                    },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(contact.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                    Text(primaryNumber, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (!contact.folderName.isNullOrBlank() || contact.tags.isNotEmpty()) {
                                        Text(
                                            buildString {
                                                contact.folderName?.takeIf { it.isNotBlank() }?.let { append(it) }
                                                if (contact.tags.isNotEmpty()) {
                                                    if (isNotEmpty()) append(" • ")
                                                    append(contact.tags.take(3).joinToString())
                                                }
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun incomingCallWindowAlignment(position: String): Alignment = when (position.uppercase()) {
    "TOP" -> Alignment.TopCenter
    "BOTTOM" -> Alignment.BottomCenter
    else -> Alignment.Center
}

private fun incomingCallCardModifier(settings: AppLockSettings): Modifier {
    val maxWidth = if (settings.incomingCallWindowSize.equals("EXPANDED", ignoreCase = true)) 560.dp else 420.dp
    return Modifier.fillMaxWidth().widthIn(max = maxWidth)
}

@Composable
private fun IncomingCallCardContainer(call: IncomingCallUiState, modifier: Modifier, afterDismiss: (() -> Unit)? = null) {
    val context = LocalContext.current
    val settings by entryPoint(context).appLockRepository().settings.collectAsStateWithLifecycle(initialValue = AppLockSettings.DEFAULT)
    val avatarBitmap = remember(call.photoUri) {
        runCatching {
            call.photoUri?.let { uri ->
                context.contentResolver.openInputStream(uri.toUri())?.use { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()
    }
    val tintAlpha = (settings.incomingCallWindowTransparency.coerceIn(20, 100) / 100f) * 0.8f
    val maxTags = if (settings.incomingCallCompactMode) 2 else 4
    val cardShape = RoundedCornerShape(34.dp)
    var showTransferDialog by remember(call.number) { mutableStateOf(false) }
    val transferCandidates = rememberTransferCandidates(context)

    if (settings.incomingCallAutoDismissSeconds > 0) {
        LaunchedEffect(call, settings.incomingCallAutoDismissSeconds) {
            delay(settings.incomingCallAutoDismissSeconds * 1000L)
            if (IncomingCallOverlayController.state.value == call) {
                dismissIncomingUi(context)
                afterDismiss?.invoke()
            }
        }
    }

    if (showTransferDialog) {
        IncomingCallTransferDialog(
            call = call,
            contacts = transferCandidates,
            onDismiss = { showTransferDialog = false },
            onTransferred = {
                showTransferDialog = false
                dismissIncomingUi(context)
                afterDismiss?.invoke()
            },
        )
    }

    Card(
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (settings.incomingCallPhotoBackgroundEnabled && settings.showPhotoInNotifications && avatarBitmap != null) {
                Image(
                    bitmap = avatarBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = tintAlpha)),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (call.blockMode.equals("SILENT_RING", ignoreCase = true)) "Incoming call • Silent" else "Incoming call",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            call.displayName,
                            style = if (settings.incomingCallCompactMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (settings.incomingCallShowNumber && call.number.isNotBlank()) {
                            Text(call.number, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = {
                        dismissIncomingUi(context)
                        afterDismiss?.invoke()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                if (!settings.incomingCallPhotoBackgroundEnabled || avatarBitmap == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .size(if (settings.incomingCallCompactMode) 62.dp else 74.dp)
                                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (settings.showPhotoInNotifications && avatarBitmap != null) {
                                Image(bitmap = avatarBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(if (settings.incomingCallCompactMode) 62.dp else 74.dp), contentScale = ContentScale.Crop)
                            } else {
                                Text(call.displayName.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        Text("Tap anywhere on the card to open the full call screen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (settings.incomingCallShowGroup && !call.folderName.isNullOrBlank()) {
                    AssistChip(onClick = {}, label = { Text(call.folderName!!) })
                }
                if (settings.incomingCallShowTag && call.tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        call.tags.take(maxTags).forEach { tag ->
                            AssistChip(onClick = {}, label = { Text(tag) })
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { showTransferDialog = true },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFFF59E0B),
                            contentColor = Color.White,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.SyncAlt, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("Transfer")
                    }
                    FilledTonalButton(
                        onClick = { declineIncomingCall(context) },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFFDC2626),
                            contentColor = Color.White,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("Decline")
                    }
                    FilledTonalButton(
                        onClick = {
                            answerIncomingCall(context)
                            afterDismiss?.invoke()
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFF16A34A),
                            contentColor = Color.White,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("Answer")
                    }
                }
            }
        }
    }
}

// ── Call reminder helper ──────────────────────────────────────────────────────

internal fun scheduleCallReminder(context: Context, number: String, name: String, delayMinutes: Int) {
    if (number.isBlank()) return
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
    val triggerAt = System.currentTimeMillis() + delayMinutes * 60_000L
    val notifIntent = Intent(context, CallReminderReceiver::class.java).apply {
        putExtra("number", number)
        putExtra("name", name)
    }
    val pi = PendingIntent.getBroadcast(
        context, number.hashCode(),
        notifIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
        alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
    } else {
        alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }
    android.widget.Toast.makeText(context, "Reminder set for $delayMinutes minutes", android.widget.Toast.LENGTH_SHORT).show()
}
