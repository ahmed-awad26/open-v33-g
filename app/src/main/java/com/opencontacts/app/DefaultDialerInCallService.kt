package com.opencontacts.app

import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.telephony.TelephonyManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import com.opencontacts.core.model.ContactSummary
import com.opencontacts.core.common.ContactCallBehaviorPreferences
import com.opencontacts.domain.contacts.ContactRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val telecomScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TelecomEntryPoint {
    fun contactRepository(): ContactRepository
}

open class DefaultDialerInCallService : InCallService() {
    private val callbacks = mutableMapOf<Call, Call.Callback>()
    private var lastEndedCallUi: ActiveCallUiState? = null

    override fun onCreate() {
        super.onCreate()
        TelecomCallCoordinator.attachService(this)
    }

    override fun onDestroy() {
        callbacks.forEach { (call, callback) -> runCatching { call.unregisterCallback(callback) } }
        callbacks.clear()
        TelecomCallCoordinator.detachService(this)
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        telecomScope.launch {
            val ui = lookupIncomingCallUi(applicationContext, call)
            val activeUi = ui.toActiveCallUiState()
            when (call.state) {
                Call.STATE_RINGING -> {
                    IncomingCallTracker.lastState = TelephonyManager.EXTRA_STATE_RINGING
                    IncomingCallTracker.lastRinging = ui
                    IncomingCallTracker.answered = false
                    IncomingCallTracker.ringStartedAtMillis = System.currentTimeMillis()
                    TelecomCallCoordinator.onIncoming(call, ui)
                    val settingsSnap = entryPoint(applicationContext).appLockRepository().settings.first()
                    val behavior = ContactCallBehaviorPreferences.getBehavior(applicationContext, ui.contactId)
                    val shouldFlash = behavior.emergencyProfile || (behavior.flashOnIncoming ?: settingsSnap.flashAlertOnIncomingCall)
                    if (shouldFlash) FlashAlertManager.startFlashing(applicationContext)
                    if (settingsSnap.soundEnabled) {
                        IncomingCallRingtonePlayer.start(applicationContext, call, ui)
                    }
                    presentIncomingCallExperience(applicationContext, ui)
                }
                Call.STATE_ACTIVE,
                Call.STATE_DIALING,
                Call.STATE_CONNECTING,
                Call.STATE_HOLDING -> {
                    TelecomCallCoordinator.onCallActive(call, activeUi)
                    launchActiveCallControls(applicationContext, activeUi, forceShow = true)
                    postOngoingCallNotification(applicationContext, activeUi)
                }
            }
        }
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                telecomScope.launch {
                    val ui = lookupIncomingCallUi(applicationContext, call)
                    val activeUi = ui.toActiveCallUiState()
                    when (state) {
                        Call.STATE_RINGING -> {
                            IncomingCallTracker.lastState = TelephonyManager.EXTRA_STATE_RINGING
                            IncomingCallTracker.lastRinging = ui
                            IncomingCallTracker.answered = false
                            IncomingCallTracker.ringStartedAtMillis = System.currentTimeMillis()
                            TelecomCallCoordinator.onIncoming(call, ui)
                            presentIncomingCallExperience(applicationContext, ui)
                            val settingsSnap = entryPoint(applicationContext).appLockRepository().settings.first()
                            val behavior = ContactCallBehaviorPreferences.getBehavior(applicationContext, ui.contactId)
                            val shouldFlash = behavior.emergencyProfile || (behavior.flashOnIncoming ?: settingsSnap.flashAlertOnIncomingCall)
                            if (shouldFlash) FlashAlertManager.startFlashing(applicationContext)
                            if (settingsSnap.soundEnabled) {
                                IncomingCallRingtonePlayer.start(applicationContext, call, ui)
                            }
                        }
                        Call.STATE_ACTIVE,
                        Call.STATE_DIALING,
                        Call.STATE_CONNECTING,
                        Call.STATE_HOLDING -> {
                            IncomingCallTracker.answered = true
                            IncomingCallTracker.lastState = TelephonyManager.EXTRA_STATE_OFFHOOK
                            FlashAlertManager.stopFlashing(applicationContext)
                            IncomingCallRingtonePlayer.stop()
                            val settingsSnap = entryPoint(applicationContext).appLockRepository().settings.first()
                            val behavior = ContactCallBehaviorPreferences.getBehavior(applicationContext, activeUi.contactId)
                            val timerSeconds = behavior.defaultCallDurationSeconds ?: if (settingsSnap.callTimerEnabled) settingsSnap.callTimerDurationSeconds else 0
                            if (timerSeconds > 0) {
                                CallTimerManager.start(applicationContext, timerSeconds)
                            }
                            TelecomCallCoordinator.onCallActive(call, activeUi)
                            if (behavior.speakerAutoOn) {
                                TelecomCallCoordinator.setAudioRoute(android.telecom.CallAudioState.ROUTE_SPEAKER)
                            }
                            lastEndedCallUi = activeUi
                            launchActiveCallControls(applicationContext, activeUi, forceShow = true)
                            postOngoingCallNotification(applicationContext, activeUi)
                            dismissFloatingIncomingCall(applicationContext)
                        }
                        Call.STATE_DISCONNECTED,
                        Call.STATE_DISCONNECTING -> {
                            FlashAlertManager.stopFlashing(applicationContext)
                            IncomingCallRingtonePlayer.stop()
                            CallTimerManager.cancel()
                            dismissIncomingUi(applicationContext)
                            dismissFloatingIncomingCall(applicationContext)
                            val settingsSnap = entryPoint(applicationContext).appLockRepository().settings.first()
                            val ringingState = IncomingCallTracker.lastRinging
                            val wasMissed = ringingState != null && !IncomingCallTracker.answered
                            if (wasMissed && settingsSnap.enableMissedCallNotification) {
                                val ringDurationSeconds = (((System.currentTimeMillis() - (IncomingCallTracker.ringStartedAtMillis ?: System.currentTimeMillis())).coerceAtLeast(0L)) / 1000L).toInt()
                                postMissedCallNotification(applicationContext, ringingState!!, settingsSnap, ringDurationSeconds)
                                if (ringDurationSeconds in 1..4) {
                                    scheduleSmartRecallReminder(applicationContext, ringingState.number, ringingState.displayName, 10)
                                }
                            } else {
                                lastEndedCallUi?.let { showPostCallQuickActions(applicationContext, it) }
                            }
                            IncomingCallTracker.lastState = TelephonyManager.EXTRA_STATE_IDLE
                            IncomingCallTracker.lastRinging = null
                            IncomingCallTracker.answered = false
                            IncomingCallTracker.ringStartedAtMillis = null
                            ActiveCallOverlayController.clear()
                            TelecomCallCoordinator.clearAll()
                            cancelOngoingCallNotification(applicationContext)
                        }
                        else -> TelecomCallCoordinator.onCallStateChanged(call)
                    }
                }
            }

            override fun onDetailsChanged(call: Call, details: Call.Details) {
                telecomScope.launch {
                    val ui = lookupIncomingCallUi(applicationContext, call)
                    if (call.state == Call.STATE_RINGING) {
                        TelecomCallCoordinator.onIncoming(call, ui)
                    } else {
                        TelecomCallCoordinator.onCallActive(call, ui.toActiveCallUiState())
                    }
                }
            }
        }
        callbacks[call] = callback
        call.registerCallback(callback)
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let { runCatching { call.unregisterCallback(it) } }
        dismissIncomingUi(applicationContext)
        dismissFloatingIncomingCall(applicationContext)
        IncomingCallRingtonePlayer.stop()
        TelecomCallCoordinator.onCallRemoved(call)
        if (TelecomCallCoordinator.activeCall.value == null) {
            ActiveCallOverlayController.clear()
            cancelOngoingCallNotification(applicationContext)
        }
        super.onCallRemoved(call)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        TelecomCallCoordinator.updateAudioState(audioState)
    }
}


private fun showPostCallQuickActions(context: Context, ui: ActiveCallUiState) {
    runCatching {
        context.startActivity(
            Intent(context, PostCallQuickActionsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("number", ui.number)
                .putExtra("name", ui.displayName)
                .putExtra("contactId", ui.contactId)
        )
    }
}

private fun scheduleSmartRecallReminder(context: Context, number: String, name: String, delayMinutes: Int) {
    if (number.isBlank()) return
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val notifIntent = Intent(context, CallReminderReceiver::class.java).apply {
        putExtra("number", number)
        putExtra("name", name)
    }
    val pi = PendingIntent.getBroadcast(
        context,
        (number + delayMinutes).hashCode(),
        notifIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val triggerAt = System.currentTimeMillis() + delayMinutes * 60_000L
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    } else {
        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }
}

private suspend fun lookupIncomingCallUi(context: Context, call: Call): IncomingCallUiState {
    val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, IncomingCallEntryPoint::class.java)
    val vaultId = entryPoint.vaultSessionManager().activeVaultId.value
    val rawNumber = call.details.handle?.schemeSpecificPart.orEmpty()
    val matched = if (vaultId == null) null else {
        val repository = EntryPointAccessors.fromApplication(context.applicationContext, TelecomEntryPoint::class.java)
            .contactRepository()
        TelecomContactResolver.resolve(vaultId, repository, rawNumber)
    }
    return matched.toIncomingCallUiState(rawNumber)
}

internal fun presentIncomingCallExperience(context: Context, ui: IncomingCallUiState) {
    val settings = runCatching { kotlinx.coroutines.runBlocking { entryPoint(context).appLockRepository().settings.first() } }.getOrDefault(com.opencontacts.core.crypto.AppLockSettings.DEFAULT)
    if (settings.flashAlertOnIncomingCall) {
        FlashAlertManager.startFlashing(context)
    }
    if (ui.blockMode.equals("INSTANT_REJECT", ignoreCase = true)) {
        TelecomCallCoordinator.decline()
        dismissIncomingUi(context)
        dismissFloatingIncomingCall(context)
        return
    }
    if (ui.blockMode.equals("SILENT_RING", ignoreCase = true)) {
        IncomingCallOverlayController.clear()
        dismissFloatingIncomingCall(context)
        postIncomingCallNotification(context, ui, settings)
        return
    }
    IncomingCallOverlayController.show(ui)
    postIncomingCallNotification(context, ui, settings)
    dismissFloatingIncomingCall(context)
    runCatching {
        context.startActivity(
            android.content.Intent(context, IncomingCallOverlayActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }
}
