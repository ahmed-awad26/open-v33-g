package com.opencontacts.core.common

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ContactCallBehavior(
    val defaultSimHandleId: String? = null,
    val flashOnIncoming: Boolean? = null,
    val defaultCallDurationSeconds: Int? = null,
    val speakerAutoOn: Boolean = false,
    val quickSmsTemplate: String? = null,
    val highPriorityNotifications: Boolean = false,
    val emergencyProfile: Boolean = false,
)

data class ContactSmsJournalEntry(
    val id: Long = System.currentTimeMillis(),
    val body: String,
    val simLabel: String? = null,
    val delivered: Boolean = false,
    val templateUsed: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

object ContactCallBehaviorPreferences {
    private const val PREFS = "opencontacts_contact_call_behavior"
    private const val BEHAVIOR_PREFIX = "behavior_"
    private const val JOURNAL_PREFIX = "journal_"

    fun getBehavior(context: Context, contactId: String?): ContactCallBehavior {
        val key = contactId?.trim().takeUnless { it.isNullOrBlank() } ?: return ContactCallBehavior()
        val raw = prefs(context).getString(BEHAVIOR_PREFIX + key, null) ?: return ContactCallBehavior()
        return runCatching {
            val json = JSONObject(raw)
            ContactCallBehavior(
                defaultSimHandleId = json.optString("defaultSimHandleId").ifBlank { null },
                flashOnIncoming = if (json.has("flashOnIncoming")) json.optBoolean("flashOnIncoming") else null,
                defaultCallDurationSeconds = json.optInt("defaultCallDurationSeconds").takeIf { it > 0 },
                speakerAutoOn = json.optBoolean("speakerAutoOn", false),
                quickSmsTemplate = json.optString("quickSmsTemplate").ifBlank { null },
                highPriorityNotifications = json.optBoolean("highPriorityNotifications", false),
                emergencyProfile = json.optBoolean("emergencyProfile", false),
            )
        }.getOrElse { ContactCallBehavior() }
    }

    fun setBehavior(context: Context, contactId: String, behavior: ContactCallBehavior) {
        val key = contactId.trim()
        if (key.isBlank()) return
        val json = JSONObject().apply {
            put("defaultSimHandleId", behavior.defaultSimHandleId)
            if (behavior.flashOnIncoming != null) put("flashOnIncoming", behavior.flashOnIncoming)
            put("defaultCallDurationSeconds", behavior.defaultCallDurationSeconds ?: 0)
            put("speakerAutoOn", behavior.speakerAutoOn)
            put("quickSmsTemplate", behavior.quickSmsTemplate.orEmpty())
            put("highPriorityNotifications", behavior.highPriorityNotifications)
            put("emergencyProfile", behavior.emergencyProfile)
        }
        prefs(context).edit().putString(BEHAVIOR_PREFIX + key, json.toString()).apply()
    }

    fun getJournal(context: Context, contactId: String?): List<ContactSmsJournalEntry> {
        val key = contactId?.trim().takeUnless { it.isNullOrBlank() } ?: return emptyList()
        val raw = prefs(context).getString(JOURNAL_PREFIX + key, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(
                        ContactSmsJournalEntry(
                            id = obj.optLong("id", System.currentTimeMillis()),
                            body = obj.optString("body"),
                            simLabel = obj.optString("simLabel").ifBlank { null },
                            delivered = obj.optBoolean("delivered", false),
                            templateUsed = obj.optString("templateUsed").ifBlank { null },
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    fun addJournalEntry(context: Context, contactId: String?, entry: ContactSmsJournalEntry) {
        val key = contactId?.trim().takeUnless { it.isNullOrBlank() } ?: return
        val current = getJournal(context, key).toMutableList()
        current.add(0, entry)
        val arr = JSONArray()
        current.take(25).forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("body", item.body)
                put("simLabel", item.simLabel.orEmpty())
                put("delivered", item.delivered)
                put("templateUsed", item.templateUsed.orEmpty())
                put("timestamp", item.timestamp)
            })
        }
        prefs(context).edit().putString(JOURNAL_PREFIX + key, arr.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
