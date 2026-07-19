package com.famviva.camara.data

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * OS-level geofence around the saved home for automatic away mode. The 15-min poll already derives
 * away/home from distance, but a geofence gives near-instant ENTER/EXIT transitions (the phone's
 * fused hardware wakes us even during Doze) and keeps [AwayModeStore.lastAutoAway] fresh between
 * polls — so the poll's "no fresh fix" fallback stays accurate. The poll remains as belt-and-braces
 * because the OS silently drops geofences on reboot / Play-services updates (re-registered on boot).
 *
 * Every entry point is a no-op unless AUTO is on, a home is set, and the required location grants are
 * held — so callers never have to check first.
 */
object GeofenceManager {
    private const val GEOFENCE_ID = "home"

    /** Broadcast target the OS invokes on a transition. Must be MUTABLE: the system fills in the
     *  transition extras. Stable request code + UPDATE_CURRENT so re-registering reuses it. */
    private fun transitionIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    /**
     * (Re)registers the home geofence. Returns true if a geofence is now active. Safe to call
     * repeatedly (it replaces any existing one). The initial ENTER|EXIT trigger fires a transition
     * for the current position right away, so [AwayModeStore.lastAutoAway] is corrected on setup.
     */
    @SuppressLint("MissingPermission") // guarded by the permission checks below
    fun register(context: Context): Boolean {
        val store = AwayModeStore(context)
        if (store.mode != AwayMode.AUTO || !store.hasHome()) return false
        // Geofencing needs background location on API 29+ (and fine location everywhere).
        if (!LocationProvider.hasForegroundPermission(context) ||
            !LocationProvider.hasBackgroundPermission(context)
        ) {
            return false
        }
        val lat = store.homeLat ?: return false
        val lng = store.homeLng ?: return false

        val geofence = Geofence.Builder()
            .setRequestId(GEOFENCE_ID)
            .setCircularRegion(lat, lng, store.homeRadiusM)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(
                GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT,
            )
            .addGeofence(geofence)
            .build()

        return runCatching {
            LocationServices.getGeofencingClient(context)
                .addGeofences(request, transitionIntent(context))
            store.geofenceActive = true
            true
        }.getOrElse {
            store.geofenceActive = false
            false
        }
    }

    /** Removes the home geofence (e.g. when the user switches back to MANUAL). */
    fun remove(context: Context) {
        runCatching {
            LocationServices.getGeofencingClient(context).removeGeofences(transitionIntent(context))
        }
        AwayModeStore(context).geofenceActive = false
    }
}
