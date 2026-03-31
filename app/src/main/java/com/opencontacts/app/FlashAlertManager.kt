package com.opencontacts.app

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * FlashAlertManager — flashes the camera torch on incoming calls.
 *
 * Pattern: 3 fast blinks (120ms on/120ms off) then 800ms pause, repeat.
 * Uses CameraManager.TorchCallback to detect if torch is externally taken
 * and stops gracefully.
 *
 * Thread-safe: all torch calls on a dedicated Handler + coroutine on IO.
 */
object FlashAlertManager {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var torchAvailable = false
    @Volatile private var cameraId: String? = null

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeUnavailable(cId: String) {
            if (cId == cameraId) { torchAvailable = false; job?.cancel() }
        }
        override fun onTorchModeChanged(cId: String, enabled: Boolean) {
            // torch changed externally — do nothing, our loop controls it
        }
    }

    fun startFlashing(context: Context) {
        stopFlashing(context)
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return

        // Find rear camera with flash
        val id = try {
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) { null } ?: return

        cameraId = id
        torchAvailable = true
        cm.registerTorchCallback(torchCallback, mainHandler)

        job = scope.launch {
            try {
                while (isActive && torchAvailable) {
                    // 3 blinks
                    repeat(3) {
                        if (!isActive || !torchAvailable) return@repeat
                        setTorch(cm, id, true)
                        delay(120)
                        setTorch(cm, id, false)
                        delay(120)
                    }
                    // pause
                    delay(800)
                }
            } finally {
                setTorch(cm, id, false)
            }
        }
    }

    fun stopFlashing(context: Context) {
        job?.cancel()
        job = null
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        try { cm.unregisterTorchCallback(torchCallback) } catch (_: Exception) {}
        cameraId?.let { id ->
            try { cm.setTorchMode(id, false) } catch (_: Exception) {}
        }
        cameraId = null
        torchAvailable = false
    }

    private fun setTorch(cm: CameraManager, id: String, on: Boolean) {
        try { cm.setTorchMode(id, on) } catch (_: Exception) {}
    }
}
