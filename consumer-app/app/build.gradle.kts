plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.famviva.camara"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.famviva.camara"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // AppCompat: powers the in-app language switch (per-app locales down to minSdk 26)
    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Video player (progressive streaming from Drive + live RTSP from the camera on the LAN)
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // Encrypted storage for the camera's RTSP credentials (entered once in-app, never in git)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Google sign-in + Authorization API (OAuth token for Drive)
    implementation("com.google.android.gms:play-services-auth:21.3.0")

    // Fused location: powers the automatic (geofence-style) away mode — the poll checks distance
    // from a saved home. No OS geofencing needed since alerts already come from the 15-min poll.
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // OpenStreetMap map for the away-mode home picker (no API key / billing, unlike Google Maps):
    // shows the saved home and its radius so the user can confirm the spot.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // HTTP client for the Drive REST API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Thumbnail loading (Drive thumbnailLink) with an authorization header
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Background polling to notify about new events
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
