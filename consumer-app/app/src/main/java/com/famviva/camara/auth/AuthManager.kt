package com.famviva.camara.auth

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Obtains an OAuth access token with the drive.readonly scope via the Google Play Services
 * Authorization API. Caches the token in memory; if it expires (HTTP 401), [invalidate] is called
 * and the next request re-authorizes.
 *
 * Must be constructed in onCreate (it registers an ActivityResultLauncher).
 */
class AuthManager(activity: ComponentActivity) {

    private val client = Identity.getAuthorizationClient(activity)
    private val scopes = listOf(Scope("https://www.googleapis.com/auth/drive.readonly"))

    @Volatile private var token: String? = null
    private var pending: ((Result<String>) -> Unit)? = null

    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            val cb = pending; pending = null
            try {
                val result = client.getAuthorizationResultFromIntent(res.data)
                val t = result.accessToken ?: error("No access token after consent")
                token = t
                cb?.invoke(Result.success(t))
            } catch (e: Exception) {
                cb?.invoke(Result.failure(e))
            }
        }

    fun invalidate() { token = null }

    /** Returns a valid token, launching the consent flow the first time if needed. */
    suspend fun token(): String {
        token?.let { return it }
        return suspendCancellableCoroutine { cont ->
            authorize { cont.resumeWith(it) }
        }
    }

    private fun authorize(cb: (Result<String>) -> Unit) {
        val request = AuthorizationRequest.builder().setRequestedScopes(scopes).build()
        client.authorize(request)
            .addOnSuccessListener { result ->
                val pi = result.pendingIntent
                if (result.hasResolution() && pi != null) {
                    pending = cb
                    runCatching {
                        launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    }.onFailure { pending = null; cb(Result.failure(it)) }
                } else {
                    val t = result.accessToken
                    if (t != null) { token = t; cb(Result.success(t)) }
                    else cb(Result.failure(IllegalStateException("No token and no resolution")))
                }
            }
            .addOnFailureListener { cb(Result.failure(it)) }
    }
}

/** Exception used to signal an expired/insufficient token (HTTP 401). */
class UnauthorizedException(msg: String) : Exception(msg)

/**
 * Gets a Drive access token WITHOUT interaction (for background work).
 * Returns null if it requires user consent or fails. Reuses the grant already given.
 */
suspend fun headlessDriveToken(context: Context): String? {
    val client = Identity.getAuthorizationClient(context)
    val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.readonly")))
        .build()
    return suspendCancellableCoroutine { cont ->
        client.authorize(request)
            .addOnSuccessListener { r -> cont.resume(if (r.hasResolution()) null else r.accessToken) }
            .addOnFailureListener { cont.resume(null) }
    }
}
