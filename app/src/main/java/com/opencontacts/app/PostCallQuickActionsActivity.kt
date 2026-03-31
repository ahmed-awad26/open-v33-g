package com.opencontacts.app

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.opencontacts.core.ui.theme.OpenContactsTheme

class PostCallQuickActionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent.getStringExtra("number").orEmpty()
        val name = intent.getStringExtra("name").orEmpty().ifBlank { number }
        val contactId = intent.getStringExtra("contactId")
        setContent {
            OpenContactsTheme {
                Surface(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f))) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        PostCallQuickActionsSheet(
                            number = number,
                            name = name,
                            onAddNote = {
                                startActivity(
                                    Intent(this@PostCallQuickActionsActivity, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                        .putExtra(EXTRA_OPEN_CONTACT_ID, contactId)
                                )
                                finish()
                            },
                            onSetReminder = {
                                scheduleReminder(number, name, 10)
                                finish()
                            },
                            onRecallLater = {
                                scheduleReminder(number, name, 30)
                                finish()
                            },
                            onSendSms = {
                                startActivity(
                                    Intent(this@PostCallQuickActionsActivity, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                        .putExtra("navigate_to", "settings/smscomposer")
                                        .putExtra("sms_to", number)
                                        .putExtra("sms_name", name)
                                        .putExtra("sms_contact_id", contactId)
                                )
                                finish()
                            },
                            onMoveToFolder = {
                                startActivity(
                                    Intent(this@PostCallQuickActionsActivity, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                        .putExtra(EXTRA_OPEN_CONTACT_ID, contactId)
                                )
                                finish()
                            },
                            onChangeTag = {
                                startActivity(
                                    Intent(this@PostCallQuickActionsActivity, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                        .putExtra(EXTRA_OPEN_CONTACT_ID, contactId)
                                )
                                finish()
                            },
                            onDismiss = { finish() },
                        )
                    }
                }
            }
        }
    }

    private fun scheduleReminder(number: String, name: String, minutes: Int) {
        if (number.isBlank()) return
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            this,
            (number + minutes).hashCode(),
            Intent(this, CallReminderReceiver::class.java).putExtra("number", number).putExtra("name", name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L
        am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }
}

@Composable
private fun PostCallQuickActionsSheet(
    number: String,
    name: String,
    onAddNote: () -> Unit,
    onSetReminder: () -> Unit,
    onRecallLater: () -> Unit,
    onSendSms: () -> Unit,
    onMoveToFolder: () -> Unit,
    onChangeTag: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("After call", style = MaterialTheme.typography.titleLarge)
            Text(name, style = MaterialTheme.typography.titleMedium)
            if (number.isNotBlank() && number != name) {
                Text(number, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PostActionTile("Add note", Icons.Default.NoteAdd, Modifier.weight(1f), onAddNote)
                PostActionTile("Remind 10m", Icons.Default.Schedule, Modifier.weight(1f), onSetReminder)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PostActionTile("Recall later", Icons.Default.Call, Modifier.weight(1f), onRecallLater)
                PostActionTile("Send SMS", Icons.Default.Message, Modifier.weight(1f), onSendSms)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PostActionTile("Move to folder", Icons.Default.Folder, Modifier.weight(1f), onMoveToFolder)
                PostActionTile("Change tag", Icons.Default.Label, Modifier.weight(1f), onChangeTag)
            }
            Text(
                "Dismiss",
                modifier = Modifier.fillMaxWidth().clickable { onDismiss() }.padding(vertical = 14.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PostActionTile(title: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier = modifier.clickable { onClick() }, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
    }
}
