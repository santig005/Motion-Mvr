package com.famviva.camara.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Persistent map of clip base-name → [ClipLabel], the on-device classifier's verdict for each clip.
 * This is what turns Phase-1 people-detection from a one-shot alert filter into a lasting property of
 * a clip: the clip card reads it here to draw a 👤 badge, and it survives app restarts.
 *
 * JSON-backed (like [CatalogStore], [OfflineStore] and friends) rather than Room — the app builds with
 * `gradle --offline` and Room's compiler isn't in the local cache. Keyed by the NVR base name
 * (`mt_YYYYMMDD_HHMMSS`, no extension), the same key the thumbnail archive uses, so the two line up.
 *
 * Writes are atomic (`.tmp` + rename) so a crash mid-save can't corrupt the file into an unparseable
 * blob that would lose every label at once.
 */
class LabelStore(context: Context) {
    private val file = File(context.filesDir, FILE)
    private val map: MutableMap<String, ClipLabel> = load()

    @Synchronized
    private fun load(): MutableMap<String, ClipLabel> {
        val out = HashMap<String, ClipLabel>()
        if (!file.exists()) return out
        runCatching {
            val j = JSONObject(file.readText())
            for (key in j.keys()) {
                // A label we no longer recognise (renamed enum) is skipped, never a crash.
                runCatching { ClipLabel.valueOf(j.getString(key)) }.getOrNull()?.let { out[key] = it }
            }
        }
        return out
    }

    @Synchronized
    private fun persist() {
        val j = JSONObject()
        map.forEach { (k, v) -> j.put(k, v.name) }
        runCatching {
            val tmp = File(file.parentFile, "$FILE.tmp")
            tmp.writeText(j.toString())
            tmp.renameTo(file)
        }
    }

    /** The classifier's verdict for [baseName] (no extension), or null if never classified. */
    @Synchronized
    fun get(baseName: String): ClipLabel? = map[baseName]

    @Synchronized
    fun has(baseName: String): Boolean = map.containsKey(baseName)

    /** A snapshot copy for the UI to read without holding the lock. */
    @Synchronized
    fun all(): Map<String, ClipLabel> = HashMap(map)

    /** Record one verdict and persist immediately (used on the new-clip path, one at a time). */
    @Synchronized
    fun put(baseName: String, label: ClipLabel) {
        map[baseName] = label
        persist()
    }

    /** Batch variant for the backfill: fold in many verdicts, then a single write. */
    @Synchronized
    fun putAll(labels: Map<String, ClipLabel>) {
        if (labels.isEmpty()) return
        map.putAll(labels)
        persist()
    }

    private companion object {
        const val FILE = "clip_labels.json"
    }
}
