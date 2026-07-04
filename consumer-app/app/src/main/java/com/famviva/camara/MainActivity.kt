package com.famviva.camara

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.famviva.camara.auth.AuthManager
import com.famviva.camara.data.DriveClient
import com.famviva.camara.data.SeenStore
import com.famviva.camara.notify.NewClipsWorker
import com.famviva.camara.ui.AppNav
import com.famviva.camara.ui.theme.CamaraTheme

// AppCompatActivity so the in-app language switch (AppCompatDelegate.setApplicationLocales) works
// down to minSdk 26; it stays a ComponentActivity, so Compose and ActivityResult APIs are unchanged.
class MainActivity : AppCompatActivity() {

    private lateinit var auth: AuthManager

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

        maybeRequestNotificationPermission()
        NewClipsWorker.schedule(applicationContext)

        enableEdgeToEdge()
        setContent {
            CamaraTheme {
                AppNav(drive = drive, seenStore = seenStore, tokenProvider = { auth.token() })
            }
        }
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
