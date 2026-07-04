package com.famviva.camara.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** One battery reading the app observed from the NVR's status.json heartbeat. */
data class BatterySample(val epochSec: Long, val battery: Int, val charging: Boolean)

/**
 * App-private, longer-lived battery time series. The NVR's own battery-history file is ephemeral
 * (a rolling ~4h window, reset on every charging transition), so instead of depending on it, the
 * app records each `battery`/`charging` observation it sees from [CameraHealth] over time — keyed by
 * the heartbeat's `updated` timestamp so repeated app loads within the same ~20min heartbeat don't
 * duplicate a point. Resolution is bounded by how often the app actually checks the camera.
 */
class BatteryHistoryStore(context: Context) {
    private val file = File(context.filesDir, "battery_history.json")
    private val cap = 2000  // ~a month at a 20-min cadence; oldest points drop past this

    /** Appends one reading for [camera], unless a point already exists at this heartbeat timestamp. */
    fun record(camera: String, epochSec: Long, battery: Int, charging: Boolean) {
        if (epochSec <= 0) return
        val all = loadRaw().toMutableList()
        if (all.any { it.first == camera && it.second.epochSec == epochSec }) return
        all += camera to BatterySample(epochSec, battery, charging)
        val trimmed = if (all.size > cap) all.sortedBy { it.second.epochSec }.takeLast(cap) else all
        save(trimmed)
    }

    /** That camera's readings, oldest first. */
    fun samples(camera: String): List<BatterySample> =
        loadRaw().filter { it.first == camera }.map { it.second }.sortedBy { it.epochSec }

    private fun loadRaw(): List<Pair<String, BatterySample>> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                o.optString("cam") to BatterySample(o.getLong("t"), o.getInt("b"), o.optBoolean("c"))
            }
        }.getOrDefault(emptyList())
    }

    private fun save(items: List<Pair<String, BatterySample>>) {
        val arr = JSONArray()
        items.forEach { (cam, s) ->
            arr.put(
                JSONObject().apply {
                    put("cam", cam)
                    put("t", s.epochSec)
                    put("b", s.battery)
                    put("c", s.charging)
                },
            )
        }
        runCatching { file.writeText(arr.toString()) }
    }
}
