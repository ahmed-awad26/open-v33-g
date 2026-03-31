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

/**
 * Receives the "remind me later" alarm and shows a notification
 * to call the missed caller back.
 */
class CallReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra("number") ?: return
        val name = intent.getStringExtra("name") ?: number

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "call_reminders"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Call reminders", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Remind me to call back" }
            )
        }

        val callIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val callPi = PendingIntent.getActivity(
            context, number.hashCode() + 1, callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle("Reminder: Call $name back")
            .setContentText("You asked to be reminded to call $number")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(callPi)
            .addAction(android.R.drawable.sym_call_outgoing, "Call now", callPi)
            .build()

        nm.notify(("reminder_$number").hashCode(), notification)
    }
}
