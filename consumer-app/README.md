# Viewer app (Android)

An Android app (Kotlin + Jetpack Compose + Media3) that lists the motion clips the NVR uploads to
Google Drive (`mt_YYYYMMDD_HHMMSS.mp4`), groups them by day, and plays each one **inside the app**
(no Drive "open with" round-trip).

The UI is available in **English and Spanish**, switchable at runtime from the top bar.

## Architecture

- **Source:** Google Drive (clips are already uploaded there by `termux/cloud-sync.sh`).
- **Auth:** Google Authorization API → OAuth access token with the `drive.readonly` scope.
- **Listing:** Drive REST v3 (`files.list`, `name contains 'mt_'`); pairs each clip with its
  sibling `mt_*.jpg` thumbnail and enriches it from `metrics.csv` (exact duration, motion intensity).
- **Playback:** Media3/ExoPlayer, progressive streaming of `?alt=media` with an
  `Authorization: Bearer <token>` header (works because the NVR muxes with `+faststart`).
- **Health:** reads each camera's `status.json` and surfaces "camera down / not reporting / low
  battery" as banners and notifications.
- **Localization:** UI strings live in `res/values/` (English) and `res/values-es/` (Spanish); the
  in-app switch uses AppCompat per-app locales.

Screens: **Days** → **Clips of a day** → **Player**.

## Requirements

- Android Studio + JDK 17. SDK android-36 + build-tools 36.
- `compileSdk/targetSdk = 36`, `minSdk = 26`.

## Required one-time setup: Google OAuth

Without this, sign-in fails. Do it once with the Google account that **stores the clips**.

1. **Build and run the app once** (even if sign-in fails) so Android Studio creates the debug
   keystore. Then get its SHA-1:
   ```
   keytool -list -v -keystore "%USERPROFILE%\.android\debug.keystore" \
     -alias androiddebugkey -storepass android -keypass android
   ```
   Copy the `SHA1: ...` line.

2. In https://console.cloud.google.com:
   - Create (or pick) a project.
   - **APIs & Services → Library →** enable **Google Drive API**.
   - **OAuth consent screen:** type *External*, mode *Testing*. Add your email (and anyone who will
     use the app) under **Test users**. Scope: `drive.readonly`.
   - **Credentials → Create credentials → OAuth client ID → Android:**
     - Package name: `com.famviva.camara`
     - SHA-1: the one from step 1.

3. Reopen the app → sign in → consent → you'll see the days with events.

> Note: the Authorization API access token expires (~1 h). The app re-authorizes automatically on a
> 401. A **release** APK has a different SHA-1 → add it in Credentials when you distribute.

## Build

Open `consumer-app/` in Android Studio (Gradle Sync creates the wrapper and `local.properties`).
Or via CLI once the wrapper is generated:
```
gradlew :app:assembleDebug
```

## Roadmap

- Instant push (FCM) instead of the ~15-min WorkManager poll.
- Distinguish clips per camera (today it groups every `mt_`).
- Listing cache + incremental refresh.
- "Person" label once Frigate is in the loop.
