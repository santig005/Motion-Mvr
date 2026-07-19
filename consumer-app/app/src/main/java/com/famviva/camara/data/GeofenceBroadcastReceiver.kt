package com.famviva.camara.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * Receives home-geofence ENTER/EXIT transitions and writes the result into the same
 * [AwayModeStore.lastAutoAway] the 15-min poll maintains — so between polls the away/home state (and
 * therefore whether motion alerts fire) is always current. Only acts while AUTO is on; MANUAL owns
 * the state by hand. Alerts themselves are still delivered by the poll, which reads this flag.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val store = AwayModeStore(context)
        if (store.mode != AwayMode.AUTO) return   // MANUAL: ignore, the user drives the state

        when (event.geofenceTransition) {
            // Arrived inside the home radius -> Home (alerts muted).
            Geofence.GEOFENCE_TRANSITION_ENTER, Geofence.GEOFENCE_TRANSITION_DWELL ->
                store.lastAutoAway = false
            // Left the home radius -> Away (alerts on).
            Geofence.GEOFENCE_TRANSITION_EXIT ->
                store.lastAutoAway = true
        }
    }
}
