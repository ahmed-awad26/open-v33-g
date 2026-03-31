package com.opencontacts.core.common

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

object IncomingRingtonePreferences {
    private const val PREFS = "opencontacts_incoming_ringtone_prefs"
    private const val CONTACT_PREFIX = "contact_ringtone_"
    private const val SIM_PREFIX = "sim_ringtone_"

    fun getContactRingtoneUri(context: Context, contactId: String?): String? {
        val key = contactId?.takeIf { it.isNotBlank() } ?: return null
        return prefs(context).getString(CONTACT_PREFIX + key, null)
    }

    fun setContactRingtoneUri(context: Context, contactId: String, uri: String?) {
        val clean = contactId.trim()
        if (clean.isBlank()) return
        prefs(context).edit().apply {
            if (uri.isNullOrBlank()) remove(CONTACT_PREFIX + clean) else putString(CONTACT_PREFIX + clean, uri)
        }.apply()
    }

    fun getSimRingtoneUri(context: Context, handleId: String?): String? {
        val key = handleId?.takeIf { it.isNotBlank() } ?: return null
        return prefs(context).getString(SIM_PREFIX + key, null)
    }

    fun setSimRingtoneUri(context: Context, handleId: String, uri: String?) {
        val clean = handleId.trim()
        if (clean.isBlank()) return
        prefs(context).edit().apply {
            if (uri.isNullOrBlank()) remove(SIM_PREFIX + clean) else putString(SIM_PREFIX + clean, uri)
        }.apply()
    }

    fun resolveIncomingRingtoneUri(
        context: Context,
        contactId: String?,
        simHandleId: String?,
    ): Uri {
        val candidate = getContactRingtoneUri(context, contactId)
            ?: getSimRingtoneUri(context, simHandleId)
        return candidate?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    }

    fun describeUri(context: Context, uriString: String?): String {
        val uri = uriString?.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: return "Default ringtone"
        return runCatching {
            RingtoneManager.getRingtone(context, uri)?.getTitle(context)
        }.getOrNull().orEmpty().ifBlank { "Custom ringtone" }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
