package com.famviva.camara.data

import android.content.Context
import org.json.JSONObject

/** One camera's entry in `cameras.json`. [label] is only a display alias ("Pasillo Interior"); the
 *  camera's id (its Drive folder, "Camara1") never changes. */
data class CameraEntry(val enabled: Boolean = true, val label: String? = null)

/**
 * The camera registry the app publishes as `cameras.json` at the Drive root (multi-camera B2/F4):
 *
 *     {"updated":1790500000,"cameras":{"Camara1":{"enabled":true,"label":"Pasillo Interior"}, ...}}
 *
 * Ownership: the NVR owns EXISTENCE (a camera exists when it has an env file and records); the app
 * owns ENABLED and the label. The NVR's watchdog reads this file and gives a disabled camera no
 * session at all — no retries, no alarms. Everything unknown is ENABLED (fail-open, on both sides): a
 * camera missing from the registry, or no registry at all, keeps being watched.
 */
data class CameraRegistry(val updated: Long = 0L, val cameras: Map<String, CameraEntry> = emptyMap()) {

    fun isEnabled(id: String?): Boolean = id == null || cameras[id]?.enabled ?: true

    /** The alias when one is set, else the id itself. */
    fun labelOf(id: String): String = cameras[id]?.label?.trim()?.takeIf { it.isNotEmpty() } ?: id

    /** A copy with [id]'s entry changed and [updated] bumped to [now] (epoch seconds). */
    fun edit(id: String, now: Long, change: (CameraEntry) -> CameraEntry): CameraRegistry =
        copy(updated = now, cameras = cameras + (id to change(cameras[id] ?: CameraEntry())))

    fun toJson(): String {
        val cams = JSONObject()
        cameras.forEach { (id, e) ->
            cams.put(id, JSONObject().put("enabled", e.enabled).apply { e.label?.let { put("label", it) } })
        }
        return JSONObject().put("updated", updated).put("cameras", cams).toString()
    }

    companion object {
        /** Null for anything that is not a registry (absent, malformed) — callers then keep what they have. */
        fun parse(json: String?): CameraRegistry? = runCatching {
            val o = JSONObject(json ?: return null)
            val cams = o.getJSONObject("cameras")
            val map = LinkedHashMap<String, CameraEntry>()
            cams.keys().forEach { id ->
                val e = cams.optJSONObject(id) ?: return@forEach
                map[id] = CameraEntry(
                    enabled = e.optBoolean("enabled", true),
                    label = e.optString("label").ifBlank { null },
                )
            }
            CameraRegistry(o.optLong("updated", 0L), map)
        }.getOrNull()
    }
}

/**
 * Which registry the app keeps after reading Drive's copy, and whether it must (re)upload its own:
 * - a local edit Drive has not confirmed yet ([pendingUpload]) that is NEWER than Drive's copy (or
 *   Drive has none) is kept and pushed again;
 * - otherwise Drive's copy wins (another device may have edited it), or the local one if Drive has none.
 */
fun reconcileRegistry(local: CameraRegistry, remote: CameraRegistry?, pendingUpload: Boolean): Pair<CameraRegistry, Boolean> =
    when {
        pendingUpload && (remote == null || local.updated > remote.updated) -> local to true
        remote != null -> remote to false
        else -> local to false
    }

/** Local cache of the registry, so labels and switches work offline and in background workers.
 *  [pendingUpload] marks a local edit Drive has not confirmed yet (retried on the next load). */
class CameraRegistryStore(context: Context) {
    private val prefs = context.getSharedPreferences("camera_registry", Context.MODE_PRIVATE)

    fun load(): CameraRegistry = CameraRegistry.parse(prefs.getString(KEY, null)) ?: CameraRegistry()

    fun save(reg: CameraRegistry, pendingUpload: Boolean) {
        prefs.edit().putString(KEY, reg.toJson()).putBoolean(KEY_PENDING, pendingUpload).apply()
    }

    val pendingUpload: Boolean get() = prefs.getBoolean(KEY_PENDING, false)

    private companion object {
        const val KEY = "registry"
        const val KEY_PENDING = "pending_upload"
    }
}
