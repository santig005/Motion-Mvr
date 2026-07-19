package com.famviva.camara.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.famviva.camara.notify.DailyDigestWorker
import com.famviva.camara.notify.NewClipsWorker

/**
 * The OS drops registered geofences across a reboot, so re-register the home geofence on boot (a
 * no-op unless AUTO is on with a home set and permissions held). Also re-schedules the pollers as a
 * belt-and-braces measure (their KEEP policy makes it idempotent — WorkManager already restores its
 * own jobs after boot).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        GeofenceManager.register(context)
        NewClipsWorker.schedule(context)
        DailyDigestWorker.schedule(context)
    }
}
