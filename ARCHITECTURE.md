# Architecture

This documents the reusable design behind the NVR and the viewer app — the *why*, not a line-by-line
walkthrough. It's written to be portable to any RTSP camera and any always-on Linux host (the
reference host happens to be an Android phone running Termux).

## Goals & constraints

- **No subscription.** Record the camera's own RTSP stream and keep footage as long as we want.
- **Runs on junk hardware.** The reference recorder is a spare, low-end Android phone. That forces a
  CPU/battery budget: we can't decode a 2K stream continuously just to detect motion.
- **LAN-only camera.** The camera has no public route, so the recorder must live on the same Wi-Fi.
- **Keep the interesting moments, not everything.** Storage (phone + Drive) is finite; we want
  motion clips with a little context, not 24/7 footage.
- **Survive the real world.** Wi-Fi drops, the camera occasionally wedges, the phone reboots. The
  system has to come back on its own.

## The recording pipeline

Three cooperating loops in one process (`termux/record-preroll.sh`), sharing a ring buffer:

### 1. Segmenter (record)
A single long-lived `ffmpeg` copies the **2K main stream** into short, timestamped segments
(`seg_YYYYMMDD_HHMMSS.mp4`) inside a **ring buffer** directory that is *not* uploaded. Video is
stream-copied (no re-encode); audio is transcoded to AAC because many cameras emit PCM A-law, which
mp4 can't store by copy. Segment length tracks the camera's GOP (~12 s in practice).

Recording the full-quality stream **all the time** is what gives us **pre-roll for free**: when
motion is later detected, the seconds *before* the trigger are already on disk.

### 2. Detector (decide)
A second `ffmpeg` decodes the cheap **360p sub-stream** and runs a frame-difference filter on a
cropped ROI (`tblend=difference → threshold → signalstats.YAVG`). YAVG is the fraction of pixels
that changed; a debounce (N consecutive frames over a threshold) rejects single-frame light
flicker. Detecting on 360p instead of 2K costs roughly ⅕ the CPU and — validated day and night —
catches the same people and doorways. The detector emits **motion windows** `(start, end)` in epoch
seconds to a small file.

Detection only decides *what to keep*; it never gates recording, so a missed detection can't lose
footage that's already in the ring.

### 3. Keeper (trim + persist)
A 5 s loop that:

- **Health by freshness.** Checks whether the newest segment was written recently. That — not
  "is ffmpeg alive" — is the true "are we recording?" signal, and it drives `status.json`.
- **Builds clips.** For each *ready* motion window (its end is now in completed segments), it
  concatenates the overlapping segments and produces one clip:
  - **Pre/post-roll** come from including neighbouring segments.
  - **Content-based tail trim:** it profiles the concatenated 2K once to find the *last real motion*
    and cuts the clip at `last_motion + TAIL_PAD`. This removes the dead tail and is immune to the
    variable skew between the 360p detector clock and the 2K, so raising the merge gap never
    lengthens a clip. The same single decode also yields the motion metrics (max/mean intensity,
    frame count).
  - Re-encodes the exact `[offset, offset+dur]` window (copy can't cut finer than a GOP) with
    `+faststart` so the app can stream it progressively.
  - Writes a **thumbnail** JPG and appends a **metrics** CSV row.
- **Atomic output.** Every clip/thumbnail is written to a `.part` file and renamed on completion, so
  the uploader never sees — and never ships — a half-written file.
- **Prunes** idle segments from the ring by age.

Output layout is camera-centric and app-agnostic:
```
Cameras/<cam>/YYYY/MM/DD/mt_YYYYMMDD_HHMMSS.mp4   (+ .jpg thumbnail)
Cameras/<cam>/metrics.csv
Cameras/<cam>/status.json
```

## Upload & retention

`cloud-sync.sh` runs `rclone` on its own loop, mirroring the whole `Cameras/` tree to Google Drive.
It intentionally does **not** use `--ignore-existing`: comparing size+modtime lets it re-upload
anything that landed partial on a previous run (self-healing). It purges local files older than
`LOCAL_KEEP_DAYS` **only after** a successful upload that cycle, and trims the cloud copy at
`CLOUD_KEEP_DAYS`. No network ⇒ retry, and never delete locally.

## Resilience

- **Wake-lock** keeps the CPU running while the screen is off.
- **Watchdog** (`watchdog.sh`) is a plain loop that re-launches the recorder, uploader, `sshd` and
  wake-lock if any dies — no cron, no dependencies. It runs in its own tmux session so it survives
  SSH disconnects.
- **Boot script** (`boot-start-cam.sh`, via Termux:Boot) restarts the watchdog after a reboot.
- **Stuck-camera handling.** Some cheap cameras wedge under connection churn (RTSP returns "invalid
  data" while ping still works). Retries use **staggered backoff** (capped) to avoid hammering it,
  and the down state is surfaced to the app instead of silently spinning.

## Health signalling

The NVR writes `status.json` per camera: `recording_ok` (segment freshness), `updated` (a heartbeat
every ~20 min, independent of whether there's motion), and optional `battery`/`charging` (via
Termux:API). The app reads these and distinguishes three failure modes: **camera down** (not
recording), **not reporting** (heartbeat stale ⇒ phone probably off/offline), and **low battery**.

While discharging, the same heartbeat also fits a linear regression over a rolling ~4h window of
`(epoch, battery%)` samples (kept in a small local file, not uploaded) to derive a discharge rate
and extrapolate an ETA. Both are optional fields in `status.json` (`discharge_pct_per_h`,
`eta_minutes`) so the app can show "~4h20m left" instead of just a percentage — a rough linear
estimate, reset on every charging transition since the slope changes.

## The viewer app

A small single-Activity **Jetpack Compose** app (`consumer-app/`). Design choices:

- **Source of truth is Drive, not a server.** The clips are already uploaded, so the app is a pure
  read-only Drive client — no backend to run. Auth is the Google Authorization API with the
  `drive.readonly` scope; the access token is cached and refreshed on 401.
- **Listing.** `files.list` with `name contains 'mt_'` returns clips *and* their sibling `mt_*.jpg`
  thumbnails; the app pairs them by base name and enriches each clip with `metrics.csv`
  (exact duration, motion intensity). Everything about a clip — date, time, day-period — is derived
  from its `mt_YYYYMMDD_HHMMSS` name, so no extra metadata calls are needed.
- **Playback in-app.** Media3/ExoPlayer streams the raw bytes from `?alt=media` with an
  `Authorization: Bearer` header. This works precisely because the NVR muxes with `+faststart`
  (the moov atom is at the front), so progressive streaming starts immediately.
- **Screens:** Days → Clips of a day → Player. Plus day/period filters, unseen-clip tracking,
  pull-to-refresh, long-press to share/download, and a motion-intensity meter.
- **Notifications.** A ~15-minute WorkManager poll compares the newest clip against the last one it
  announced and posts a notification for new clips or health issues. (A future push path would have
  the NVR notify via FCM for instant alerts.)
- **Localization.** UI strings live in Android resources with English (`values/`) and Spanish
  (`values-es/`) translations; an in-app switch selects the locale at runtime.

## Why not just Frigate?

[Frigate](https://frigate.video/) is a great free NVR with real AI object detection, and there's a
config for it here. But it wants an always-on box with some horsepower (Pi 4/5 or an N100 mini-PC).
The phone-based pipeline needs *zero* extra hardware and no AI accelerator, at the cost of
"motion" instead of "person/car." Frigate is the natural upgrade once a small always-on host is
available; the storage layout and the app don't have to change.
