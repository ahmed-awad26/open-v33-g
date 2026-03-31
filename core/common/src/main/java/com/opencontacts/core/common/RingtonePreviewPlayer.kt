package com.opencontacts.core.common

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build

object RingtonePreviewPlayer {
    private var ringtone: Ringtone? = null

    fun play(context: Context, uriString: String?) {
        stop()
        val uri = uriString?.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val next = runCatching { RingtoneManager.getRingtone(context, uri) }.getOrNull() ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                next.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            next.play()
            ringtone = next
        }
    }

    fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
    }
}
