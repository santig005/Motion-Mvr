package com.famviva.camara.data

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** One camera's RTSP connection details for live view. [id] is the camera's canonical id — its Drive
 *  folder name, e.g. "Camara1" — so live view, clips and health all name a camera the same way. */
data class CameraConfig(
    val id: String,
    val host: String,
    val port: Int = CameraConfigStore.DEFAULT_PORT,
    val user: String = "",
    val password: String = "",
) {
    /**
     * The RTSP URL for the live stream. [hd] picks the 2K main stream (ch0) vs. the lighter 360p
     * sub-stream (ch1, the snappy default). Credentials are percent-encoded so special characters in
     * the password can't break the URL.
     */
    fun rtspUrl(hd: Boolean): String {
        val path = if (hd) CameraConfigStore.PATH_MAIN else CameraConfigStore.PATH_SUB
        val cred = if (user.isNotEmpty()) "${Uri.encode(user)}:${Uri.encode(password)}@" else ""
        return "rtsp://$cred$host:$port$path"
    }
}

/**
 * The cameras' RTSP connection details for live view, entered by the user in-app and stored
 * **encrypted** (EncryptedSharedPreferences, AES-256) — so the credentials never live in the repo, a
 * build config, or plain preferences. One entry per camera (multi-camera live view, 2026-09-27); the
 * single camera configured before that is migrated to [DEFAULT_ID] on first read, untouched.
 */
class CameraConfigStore(context: Context) {
    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "camera_config_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Every configured camera, in the order they were added (the first one is the "main" camera). */
    fun cameras(): List<CameraConfig> {
        migrateLegacy()
        return parseCameraIds(prefs.getString(KEY_IDS, null)).mapNotNull { get(it) }
    }

    fun get(id: String): CameraConfig? {
        val host = prefs.getString(key(id, "host"), null)?.takeIf { it.isNotBlank() } ?: return null
        return CameraConfig(
            id = id,
            host = host,
            port = prefs.getInt(key(id, "port"), DEFAULT_PORT),
            user = prefs.getString(key(id, "user"), "").orEmpty(),
            password = prefs.getString(key(id, "pass"), "").orEmpty(),
        )
    }

    /** Adds or replaces a camera. Renaming = [remove] the old id + [save] the new one. */
    fun save(cfg: CameraConfig) {
        migrateLegacy()
        val ids = parseCameraIds(prefs.getString(KEY_IDS, null))
        prefs.edit()
            .putString(key(cfg.id, "host"), cfg.host.trim())
            .putInt(key(cfg.id, "port"), cfg.port)
            .putString(key(cfg.id, "user"), cfg.user.trim())
            .putString(key(cfg.id, "pass"), cfg.password)
            .putString(KEY_IDS, joinCameraIds(ids + cfg.id))
            .apply()
    }

    fun remove(id: String) {
        val ids = parseCameraIds(prefs.getString(KEY_IDS, null))
        prefs.edit()
            .remove(key(id, "host")).remove(key(id, "port")).remove(key(id, "user")).remove(key(id, "pass"))
            .putString(KEY_IDS, joinCameraIds(ids - id))
            .apply()
    }

    /** The pre-multi-camera format kept ONE camera under flat keys. Move it to [DEFAULT_ID] once, so
     *  nobody has to re-enter credentials after the update. No-op when there is nothing to migrate. */
    private fun migrateLegacy() {
        val host = prefs.getString(LEGACY_HOST, null)
        if (host.isNullOrBlank()) return
        val ids = parseCameraIds(prefs.getString(KEY_IDS, null))
        val e = prefs.edit()
        if (DEFAULT_ID !in ids) {
            e.putString(key(DEFAULT_ID, "host"), host)
                .putInt(key(DEFAULT_ID, "port"), prefs.getInt(LEGACY_PORT, DEFAULT_PORT))
                .putString(key(DEFAULT_ID, "user"), prefs.getString(LEGACY_USER, "").orEmpty())
                .putString(key(DEFAULT_ID, "pass"), prefs.getString(LEGACY_PASS, "").orEmpty())
                .putString(KEY_IDS, joinCameraIds(listOf(DEFAULT_ID) + ids))
        }
        e.remove(LEGACY_HOST).remove(LEGACY_PORT).remove(LEGACY_USER).remove(LEGACY_PASS).commit()
    }

    private fun key(id: String, field: String) = "cam.$id.$field"

    companion object {
        /** Where the single pre-multi-camera config lands: the entrance camera's folder name. */
        const val DEFAULT_ID = "Camara1"
        const val DEFAULT_PORT = 554
        // AJCloud/FAMVIVA stream paths: ch0 = 2K main, ch1 = 360p sub.
        const val PATH_MAIN = "/live/ch0"
        const val PATH_SUB = "/live/ch1"
        private const val KEY_IDS = "camera_ids"
        private const val LEGACY_HOST = "host"
        private const val LEGACY_PORT = "port"
        private const val LEGACY_USER = "user"
        private const val LEGACY_PASS = "pass"
    }
}

/** A camera id the store can hold: non-blank, and no separator/whitespace that would break the list or
 *  the preference keys. Ids are the Drive folder names ("Camara1"), so this never bites in practice. */
fun isValidCameraId(id: String): Boolean = id.isNotBlank() && id.none { it == ',' || it.isWhitespace() || it == '.' }

/** Stored id list -> ids, order kept, blanks and duplicates dropped. */
fun parseCameraIds(raw: String?): List<String> =
    raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

fun joinCameraIds(ids: List<String>): String = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
