package com.opencontacts.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.telecom.Call
import com.opencontacts.core.common.IncomingRingtonePreferences

object IncomingCallRingtonePlayer {
    private var ringtone: Ringtone? = null
    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var previousMode: Int? = null
    private var previousSpeakerphone: Boolean? = null
    private var lastUri: Uri? = null

    fun start(context: Context, call: Call?, incoming: IncomingCallUiState?) {
        stop()
        val simHandleId = call?.details?.accountHandle?.id
        val uri = IncomingRingtonePreferences.resolveIncomingRingtoneUri(
            context = context,
            contactId = incoming?.contactId,
            simHandleId = simHandleId,
        )
        lastUri = uri
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am
        prepareAudioRoute(am)
        requestAudioFocus(am)

        if (!tryPlayRingtone(context, uri)) {
            tryPlayMediaPlayer(context, uri)
        }
    }

    fun preview(context: Context, uriString: String?) {
        stop()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am
        prepareAudioRoute(am)
        requestAudioFocus(am)
        val uri = uriString?.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        lastUri = uri
        if (!tryPlayRingtone(context, uri)) {
            tryPlayMediaPlayer(context, uri)
        }
    }

    fun restartIfNeeded(context: Context) {
        val uri = lastUri ?: return
        if (ringtone?.isPlaying == true || mediaPlayer?.isPlaying == true) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am
        prepareAudioRoute(am)
        requestAudioFocus(am)
        if (!tryPlayRingtone(context, uri)) {
            tryPlayMediaPlayer(context, uri)
        }
    }

    fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching {
            mediaPlayer?.setOnCompletionListener(null)
            mediaPlayer?.stop()
        }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        abandonAudioFocus()
        restoreAudioRoute()
        lastUri = null
    }

    private fun tryPlayRingtone(context: Context, uri: Uri): Boolean {
        val next = runCatching { RingtoneManager.getRingtone(context, uri) }.getOrNull() ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                next.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                next.isLooping = true
            }
            next.play()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || next.isPlaying) {
                ringtone = next
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private fun tryPlayMediaPlayer(context: Context, uri: Uri) {
        val player = MediaPlayer()
        runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setDataSource(context, uri)
            player.isLooping = true
            player.setOnPreparedListener { it.start() }
            player.prepareAsync()
            mediaPlayer = player
        }.onFailure {
            runCatching { player.release() }
        }
    }

    private fun prepareAudioRoute(am: AudioManager) {
        previousMode = am.mode
        previousSpeakerphone = am.isSpeakerphoneOn
        runCatching {
            am.mode = AudioManager.MODE_RINGTONE
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                am.isSpeakerphoneOn = false
            }
        }
        runCatching {
            val current = am.getStreamVolume(AudioManager.STREAM_RING)
            if (current <= 0) {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
                val safe = (max * 0.7f).toInt().coerceAtLeast(1)
                am.setStreamVolume(AudioManager.STREAM_RING, safe, 0)
            }
        }
    }

    private fun restoreAudioRoute() {
        val am = audioManager ?: return
        runCatching { previousMode?.let { am.mode = it } }
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                previousSpeakerphone?.let { am.isSpeakerphoneOn = it }
            }
        }
        previousMode = null
        previousSpeakerphone = null
        audioManager = null
    }

    private fun requestAudioFocus(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .build()
            runCatching { am.requestAudioFocus(request) }
            audioFocusRequest = request
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_RING,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                )
            }
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request -> runCatching { am.abandonAudioFocusRequest(request) } }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            runCatching { am.abandonAudioFocus(null) }
        }
    }
}
