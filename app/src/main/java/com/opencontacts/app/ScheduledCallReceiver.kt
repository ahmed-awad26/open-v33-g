package com.opencontacts.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.opencontacts.feature.dialer.ACTION_SCHEDULED_CALL_DIAL
import com.opencontacts.feature.dialer.ACTION_SCHEDULED_CALL_REMINDER
import com.opencontacts.feature.dialer.EXTRA_CALL_ID
import com.opencontacts.feature.dialer.EXTRA_CONTACT_NAME
import com.opencontacts.feature.dialer.EXTRA_PHONE_NUMBER
import com.opencontacts.feature.dialer.EXTRA_SIM_SLOT

/**
 * Handles scheduled call alarms:
 *   - REMINDER: shows a notification N minutes before with a "Dial Now" action
 *   - DIAL:     auto-initiates the call at the scheduled time
 */
class ScheduledCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val phone = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: return
        val name = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: phone
        val simSlot = intent.getIntExtra(EXTRA_SIM_SLOT, -1)
        val callId = intent.getIntExtra(EXTRA_CALL_ID, 0)

        when (intent.action) {
            ACTION_SCHEDULED_CALL_REMINDER -> showReminderNotification(context, phone, name, callId)
            ACTION_SCHEDULED_CALL_DIAL -> initiateCall(context, phone)
        }
    }

    private fun showReminderNotification(
        context: Context,
        phone: String,
        name: String,
        callId: Int,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "scheduled_calls"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Scheduled Calls", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        // "Dial Now" action
        val dialIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val dialPi = PendingIntent.getActivity(
            context, callId,
            dialIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Scheduled call reminder")
            .setContentText("You have a scheduled call with $name ($phone)")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_call, "Dial Now", dialPi)
            .build()

        nm.notify(callId, notification)
    }

    private fun initiateCall(context: Context, phone: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) { }
    }
}
