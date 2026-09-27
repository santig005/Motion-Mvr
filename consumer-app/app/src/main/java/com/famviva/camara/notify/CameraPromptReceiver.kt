package com.famviva.camara.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.famviva.camara.auth.headlessDriveToken
import com.famviva.camara.data.CameraRegistryStore
import com.famviva.camara.data.DriveClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The two answers of the "did you unplug it?" prompt.
 * - [ACTION_DISABLE]: switch the camera off exactly as the in-app switch does — local registry first
 *   (marked pending, so the app retries the upload if this one fails), then cameras.json on Drive,
 *   which the NVR's watchdog obeys within ~2 min.
 * - [ACTION_KEEP]: "still installed" — stop asking until this silence episode ends; the normal
 *   alarms stay on, because now it is a real incident.
 */
class CameraPromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val camera = intent.getStringExtra(EXTRA_CAMERA) ?: return
        val app = context.applicationContext
        NotificationManagerCompat.from(app).cancel(Notifications.unplugNotifId(camera))
        when (intent.action) {
            ACTION_KEEP -> NotifyStore(app).keepSilentCamera(camera)
            ACTION_DISABLE -> {
                val store = CameraRegistryStore(app)
                val reg = store.load().edit(camera, System.currentTimeMillis() / 1000) { it.copy(enabled = false) }
                store.save(reg, pendingUpload = true)
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val token = headlessDriveToken(app)
                        if (token != null) {
                            val ok = runCatching {
                                DriveClient(tokenProvider = { token }, onUnauthorized = {}).uploadCameraRegistry(reg.toJson())
                            }.getOrDefault(false)
                            if (ok && store.load() == reg) store.save(reg, pendingUpload = false)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_DISABLE = "com.famviva.camara.action.DISABLE_CAMERA"
        const val ACTION_KEEP = "com.famviva.camara.action.KEEP_CAMERA"
        const val EXTRA_CAMERA = "camera"
    }
}
