package com.famviva.camara.data

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The camera's RTSP connection details for live view, entered once by the user in-app and stored
 * **encrypted** (EncryptedSharedPreferences, AES-256) — so the credentials never live in the repo,
 * a build config, or plain preferences. Only used to build the local RTSP URL the player connects to
 * on the same network as the camera.
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

    val host: String get() = prefs.getString(KEY_HOST, "").orEmpty()
    val port: Int get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
    val user: String get() = prefs.getString(KEY_USER, "").orEmpty()
    val password: String get() = prefs.getString(KEY_PASS, "").orEmpty()

    /** Enough to attempt a connection: we at least need a host/IP. */
    fun isConfigured(): Boolean = host.isNotBlank()

    fun save(host: String, port: Int, user: String, password: String) {
        prefs.edit()
            .putString(KEY_HOST, host.trim())
            .putInt(KEY_PORT, port)
            .putString(KEY_USER, user.trim())
            .putString(KEY_PASS, password)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()

    /**
     * The RTSP URL for the live stream. [hd] picks the 2K main stream (ch0) vs. the lighter 360p
     * sub-stream (ch1, the snappy default). Credentials are percent-encoded so special characters in
     * the password can't break the URL. Null when nothing is configured yet.
     */
    fun rtspUrl(hd: Boolean): String? {
        if (!isConfigured()) return null
        val path = if (hd) PATH_MAIN else PATH_SUB
        val cred = if (user.isNotEmpty()) "${Uri.encode(user)}:${Uri.encode(password)}@" else ""
        return "rtsp://$cred$host:$port$path"
    }

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_USER = "user"
        const val KEY_PASS = "pass"
        const val DEFAULT_PORT = 554
        // AJCloud/FAMVIVA stream paths: ch0 = 2K main, ch1 = 360p sub.
        const val PATH_MAIN = "/live/ch0"
        const val PATH_SUB = "/live/ch1"
    }
}
