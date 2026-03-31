package com.opencontacts.app

import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.opencontacts.core.ui.theme.OpenContactsTheme
import com.opencontacts.core.common.ContactCallBehaviorPreferences

class MissedCallQuickActionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
        val number = intent.getStringExtra("number").orEmpty()
        val name = intent.getStringExtra("name").orEmpty().ifBlank { number }
        val contactId = intent.getStringExtra("contactId")
        val important = intent.getBooleanExtra("important", false)
        val repeatedWithinHour = intent.getIntExtra("repeatedWithinHour", 0)
        val unknownRepeated = intent.getBooleanExtra("unknownRepeated", false)
        val shouldAutoRemind = intent.getBooleanExtra("shouldAutoRemind", false)
        val quickTemplate = ContactCallBehaviorPreferences.getBehavior(this, contactId).quickSmsTemplate.orEmpty()
        setContent {
            OpenContactsTheme {
                Surface(color = Color(0x66000000), modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        MissedCallQuickActionsSheet(
                            number = number,
                            name = name,
                            important = important,
                            repeatedWithinHour = repeatedWithinHour,
                            unknownRepeated = unknownRepeated,
                            shouldAutoRemind = shouldAutoRemind,
                            onCallNow = {
                                startActivity(Intent(Intent.ACTION_DIAL, "tel:$number".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                finish()
                            },
                            onSms = {
                                startActivity(
                                    Intent(this@MissedCallQuickActionsActivity, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                        .putExtra("navigate_to", "settings/smscomposer")
                                        .putExtra("sms_to", number)
                                        .putExtra("sms_name", name)
                                        .putExtra("sms_contact_id", contactId)
                                        .putExtra("sms_template", quickTemplate)
                                )
                                finish()
                            },
                            onRemind = {
                                scheduleCallReminder(this@MissedCallQuickActionsActivity, number, name, 10)
                                finish()
                            },
                            onAddContact = {
                                val addIntent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                                    type = ContactsContract.RawContacts.CONTENT_TYPE
                                    putExtra(ContactsContract.Intents.Insert.PHONE, number)
                                    putExtra(ContactsContract.Intents.Insert.NAME, name)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                runCatching { startActivity(addIntent) }
                                finish()
                            },
                            onAddToFolderVault = {
                                startActivity(
                                    Intent(this@MissedCallQuickActionsActivity, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                        .putExtra("create_contact_number", number)
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
}

@Composable
private fun MissedCallQuickActionsSheet(
    number: String,
    name: String,
    important: Boolean,
    repeatedWithinHour: Int,
    unknownRepeated: Boolean,
    shouldAutoRemind: Boolean,
    onCallNow: () -> Unit,
    onSms: () -> Unit,
    onRemind: () -> Unit,
    onAddContact: () -> Unit,
    onAddToFolderVault: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (important) "Important missed call" else "Missed call", style = MaterialTheme.typography.titleLarge)
            Text(name, style = MaterialTheme.typography.titleMedium)
            if (number.isNotBlank() && number != name) Text(number, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val insights = buildList {
                if (shouldAutoRemind) add("Short ring detected — callback reminder suggested")
                if (repeatedWithinHour >= 3) add("Repeated caller ($repeatedWithinHour times in the last hour)")
                if (unknownRepeated) add("Unknown number repeated more than once — consider saving it")
            }
            insights.forEach { insight ->
                Text("• $insight", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickActionCard("Call now", Icons.Default.Call, Modifier.weight(1f), onCallNow)
                QuickActionCard("Send SMS", Icons.Default.Message, Modifier.weight(1f), onSms)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickActionCard("Remind 10m", Icons.Default.Schedule, Modifier.weight(1f), onRemind)
                QuickActionCard("Add contact", Icons.Default.PersonAdd, Modifier.weight(1f), onAddContact)
            }
            QuickActionCard("Add to folder/vault", Icons.Default.NoteAdd, Modifier.fillMaxWidth(), onAddToFolderVault)
            Spacer(Modifier.height(4.dp))
            Text(
                "Dismiss",
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).clickable { onDismiss() }.padding(vertical = 14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
private fun QuickActionCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier = modifier.clickable { onClick() }, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
    }
}
