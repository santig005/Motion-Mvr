package com.famviva.camara

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.famviva.camara.notify.Notifications
import com.famviva.camara.auth.AuthManager
import com.famviva.camara.data.BatteryHistoryStore
import com.famviva.camara.data.ClipListCache
import com.famviva.camara.data.DriveClient
import com.famviva.camara.data.FavoritesStore
import com.famviva.camara.data.GeofenceManager
import com.famviva.camara.data.OfflineStore
import com.famviva.camara.data.SeenStore
import com.famviva.camara.notify.DailyDigestWorker
import com.famviva.camara.notify.NewClipsWorker
import com.famviva.camara.ui.AppNav
import com.famviva.camara.ui.theme.CamaraTheme

// AppCompatActivity so the in-app language switch (AppCompatDelegate.setApplicationLocales) works
// down to minSdk 26; it stays a ComponentActivity, so Compose and ActivityResult APIs are unchanged.
class MainActivity : AppCompatActivity() {

    private lateinit var auth: AuthManager

    // Deep-link destination from a tapped notification (e.g. "live"). Backed by Compose state so
    // onNewIntent (activity reused, singleTop) re-triggers navigation, not just the first launch.
    private val deepLink = mutableStateOf<String?>(null)

    // Notification permission (Android 13+). Must be registered before STARTED.
    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // AuthManager registers an ActivityResultLauncher: it must be built here, in onCreate.
        auth = AuthManager(this)
        val drive = DriveClient(
            tokenProvider = { auth.token() },
            onUnauthorized = { auth.invalidate() },
        )
        val seenStore = SeenStore(applicationContext)
        val offlineStore = OfflineStore(applicationContext)
        val clipListCache = ClipListCache(applicationContext)
        val batteryHistory = BatteryHistoryStore(applicationContext)
        val favoritesStore = FavoritesStore(applicationContext)

        maybeRequestNotificationPermission()
        NewClipsWorker.schedule(applicationContext)
        DailyDigestWorker.schedule(applicationContext)
        // Re-assert the away-mode geofence (a no-op unless AUTO + home + permissions): the OS drops
        // geofences on reboot / Play-services updates, so refresh it on every cold start too.
        GeofenceManager.register(applicationContext)

        deepLink.value = intent?.getStringExtra(Notifications.EXTRA_DEST)

        enableEdgeToEdge()
        setContent {
            CamaraTheme {
                val dest by deepLink
                AppNav(
                    drive = drive,
                    seenStore = seenStore,
                    offlineStore = offlineStore,
                    clipListCache = clipListCache,
                    batteryHistory = batteryHistory,
                    favoritesStore = favoritesStore,
                    tokenProvider = { auth.token() },
                    deepLinkRoute = dest,
                    onDeepLinkHandled = { deepLink.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink.value = intent.getStringExtra(Notifications.EXTRA_DEST)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
