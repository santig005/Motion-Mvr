# Multi-camera — architecture & feature plan (1…N cameras)

Status: **design plan, written 2026-09-26.** Companion to `MULTICAMERA_PLAN.md` (which holds the
*requirements*, the three-state model, and the hardware capacity limits — all still valid). This doc
is the *how*: the concrete back-end (NVR) and front-end (app) changes to support **1 to N cameras**
cleanly, where cameras come and go (we may test with 2, drop to 1, and grow toward 4 later).

## What this session already validated (2026-09-26)

- **Two cameras, both in 2K, are stable on the current phone** (Galaxy A03, Android 13): ~13 min with
  no SUB fallback, Wi-Fi 0% loss, RAM ~1.4 GB free, Termux stable. cam2 = `192.168.101.23` (360p and
  2K both verified).
- **The real host limit was NOT RAM or battery — it was Android 12+'s phantom-process killer**, which
  kills an app's process tree past ~32 children. A second camera (each clip build = ffmpeg + ffprobe +
  thumbnail + awk) crossed it and killed all of Termux twice. Fixed persistently over ADB
  (`settings put global settings_enable_monitor_phantom_procs false` + `device_config
  max_phantom_processes` unlimited). **This is a prerequisite for N>1 and must be documented in the
  provisioning steps** (see `_private/build-and-deploy.md` TODO).

The upshot: the binding constraints for growth are, in order, **(1) Android process/host limits →
provisioning**, **(2) Google Drive quota → retention policy**, **(3) 2.4 GHz Wi-Fi contention →
per-camera quality**. None block 2 cameras today; all three bite before 4.

## Principles (what "best practices" means here)

1. **A camera is a unit, not a special case.** N=1 must not be a different code path from N=4. Today
   the NVR is already close (per-camera env / ring / status.json); the app is not (it pools all
   clips). Fix the app to the same shape.
2. **Recording is decoupled from uploading.** A camera captures into its ring and finalizes clips;
   *what* gets uploaded and *in what order* is a separate concern. This is what lets an upload queue
   exist and what stops one camera's Drive backlog from starving another's live footage.
3. **Fair, oldest-first upload across cameras.** With 4 cameras finalizing clips at once, the clip
   that finished first should upload first — regardless of which camera it came from.
4. **Correctness is a directory scan; latency is a queue.** The queue is an *optimization*. A periodic
   full scan remains the backstop that guarantees nothing is ever permanently missed (crash between
   finalize and enqueue, offline spell, corrupt partial). Never let the queue be the only thing that
   knows a clip exists.
5. **Disabled ≠ silent ≠ healthy.** Keep the three states strictly separate (from `MULTICAMERA_PLAN`):
   a camera the user turned off must generate zero retries and zero alerts; a camera that went silent
   on its own is still an alarm.
6. **Fail-open on config, fail-safe on power.** Missing/broken `cameras.json` ⇒ treat every camera as
   enabled (never silently stop surveilling). Power-cycle logic (smart plug) ⇒ never leave a camera
   unpowered.

---

## Back-end (NVR) changes

### B1. The upload queue (the core new piece)

**Today:** `cloud-sync.sh` is a single loop that walks `CAMERAS_DIR/*/` and, per camera, `rclone
copy`s that camera's *today* folder (fast lane), then periodically the whole tree (self-heal), then
runs retention. It is directory-scan driven and **sequential per camera**: camera1's whole folder is
reconciled before camera2's. At 4 cameras, a fresh clip on camera4 waits behind camera1..3.

**Proposed:** a filesystem FIFO, drained oldest-first, decoupled from any single camera.

- **Enqueue (producer = `record-preroll.sh`).** A clip is already written atomically (`.part` →
  rename) into `OUT_DIR/YYYY/MM/DD/mt_*.mp4`. Right after the rename, drop a **marker** into a shared
  queue dir: `CAMERAS_DIR/.upload_queue/`. The marker is a tiny file whose **name encodes finalize
  time + camera + clip** (e.g. `<epoch>.<cam>.<basename>`) and whose **content is the clip's absolute
  path** (+ its `.jpg` thumbnail path). Naming by epoch makes `ls` ordering == finalization order
  across all cameras, with **no lock needed** (each marker is one atomic create). This is the whole
  "first to arrive, first uploaded" property, for free.
- **Drain (consumer = a queue worker in `cloud-sync.sh`).** Loop: take the **oldest** marker
  (`ls -tr` / sort by name), `rclone copyto` that one file (and its thumbnail) to
  `REMOTE/<cam>/<date>/`, and on success delete the marker. On failure: leave the marker, classify the
  error (`classify_sync_err` already exists), back off, and move on — a stuck file must not block the
  queue head forever, so after K failures push it to the back / a `.deadletter` and let self-heal
  catch it.
- **One worker, bounded parallelism.** Keep a **single** drain worker using `rclone`'s own
  `--transfers` for in-flight parallelism. This preserves the existing hard-won protection against the
  Drive 403 quota cascade (see the `project_investigation_2026_07_16` memory / 3-lane design) — many
  independent rclone processes hammering Drive is exactly what caused it.
- **Backstop stays.** Keep the periodic **self-heal full-tree scan** (already in `cloud-sync.sh`) as
  the correctness net for anything the queue missed (producer died before enqueue, marker lost,
  partial upload). The queue replaces the *fast lane* (lower latency, fair); self-heal is unchanged.
- **Crash-safe & bounded.** Markers are idempotent (uploading an already-present file is a no-op for
  rclone). The queue dir self-empties; cap it and, if it ever grows unbounded (Drive down for hours),
  that's a surfaced health signal, not a memory leak (markers are bytes on disk, not RAM).

Why a marker *directory* over an append-only queue file: no `flock` races between N producers, each
enqueue is a single atomic `create`, and recovery is just "list the dir." It fits a phone with no
real IPC.

**Latency budget unchanged for N=1**, and for N>1 the worst-case per-clip latency becomes "sum of the
clips ahead of me in global finalize order," not "sum of every earlier camera's whole folder."

### B2. Camera registry + enable/disable (`cameras.json`)

From `MULTICAMERA_PLAN.md` (decided): the app writes `cameras.json` at the Drive root (same round trip
as `favorites.json`, already proven); the NVR reads it (`rclone cat`, cached locally). The **NVR owns
existence** (`~/camN.env` exists ⇒ camera exists), the **app owns enabled/disabled**.

- `watchdog.sh` already takes `CAMS="cam1 cam2 …"`. Make the effective `CAMS` = configured envs ∩
  enabled-in-`cameras.json`. `ensure_session` only for enabled cameras; **actively `tmux kill-session`
  for a camera that flipped to disabled** (today the experiment ran cam2 *outside* the watchdog by
  hand — the productized path is: the watchdog owns every enabled camera).
- Fail-open: unreadable/missing/malformed `cameras.json` ⇒ all configured cameras enabled.
- A disabled camera keeps its archive (past clips stay browsable); disabling only affects the future.

### B3. Watchdog: N-camera supervision (mostly already there)

- Per-camera detector/segmenter/wedge tracking already keys on `$cam`. Verify no cross-camera state
  bleed once N>1 is the normal path.
- **Watch `sshd` and re-`termux-wake-lock`** in the watchdog loop (this session lost sshd when Termux
  was culled). Cheap insurance for a headless host.
- Per-camera reboot/power-cycle (smart plug) already keys its guards per camera via `$RING_DIR/.reboots`
  — one plug per camera, or a plug map, later.

### B4. Per-camera telemetry — remove the shared-file concurrency hazard

`status.json`, ring, `.health_acc_*`, `.battery_hist_*` are already per-camera. But `events.jsonl`,
`wifi.jsonl`, `daily_health.jsonl` are **shared files at `CAMERAS_DIR` root**, written by each
camera's keeper — with two keepers this is a concurrent-append hazard (this session sidestepped it by
pointing cam2 at `*_cam2.jsonl` files). Productized options:

- **(a) Per-camera files** `events_<cam>.jsonl`, `wifi_<cam>.jsonl`, `daily_health_<cam>.jsonl`; the
  app reads and merges. Simplest, zero contention, natural for per-camera views. **Recommended.**
- (b) A single **serializer** process that owns the shared logs; keepers hand it lines over a fifo.
  More moving parts on a phone; only worth it if a truly global ordered event stream is needed.

Recommend (a): it also makes per-camera health/storage in the app a direct read.

### B5. Retention & Drive quota with N cameras

- Drive free tier (15 GB) is the hard wall: ~200 MB/day/camera in 2K. 2 cams ≈ 12 GB/30 d (tight),
  3–4 cams overflow. **Make cloud retention per-camera** (`CLOUD_KEEP_DAYS` per env): the main entrance
  keeps 30 d, a test/second camera keeps 3–7 d. Local retention (phone + archive phone) is not the
  constraint.
- Surface the projection in the app (storage screen already has the data): "at current rate, N
  cameras × K days = X GB — over quota in D days."
- Consider **per-camera default quality**: a second camera defaults to 360p unless explicitly set to
  2K (RAM/Wi-Fi/quota headroom). This session showed 2K×2 works short-term; the nightly 2.4 GHz
  congestion window (see memory) is the thing to watch over a full day.

### B6. Provisioning (host limits) — must be written down

The phantom-process-killer disable is now load-bearing for N>1 and is invisible until it bites. Add to
`_private/build-and-deploy.md` and the boot checklist:

- `adb shell settings put global settings_enable_monitor_phantom_procs false`
- `adb shell device_config set_sync_disabled_for_tests persistent`
- `adb shell device_config put activity_manager max_phantom_processes 2147483647`
- (existing) battery optimization off for Termux + Termux:Boot; "stay awake while charging"; keep
  plugged in.

---

## Front-end (app) changes

The app is where "1 vs N" is currently a special case and shouldn't be. Grounded in the code refs from
`MULTICAMERA_PLAN.md` (`DriveClient.kt`, `Models.kt`, `AppNav.kt`).

### F1. Per-camera data model (the enabling change)

- `DriveClient.listClips()` today **drops the camera** from the Drive path — every `mt_*` is pooled.
  Request `parents`/path in the Drive `fields` query and derive the camera; add a **`camera` field to
  `Clip` / `ClipRecord`**. Everything else (filters, favorites, archive, latency) keys off this.
- `fetchCameraHealth` / `CameraHealth` already per-camera; wire it to a **list** of cameras from
  `cameras.json` rather than a single hard-coded one.

### F2. Camera picker + merged view

- A **camera selector** (chips / dropdown) with per-camera view **and** an **"All cameras"** merged
  timeline (the user wants both). Merged view = clips from all enabled cameras interleaved by time,
  each tagged with its camera label.
- **N=1 must not regress:** with a single camera the selector collapses/hides and the UX is exactly
  today's. The selector appears only when >1 camera exists.

### F3-live. Live multi-view (holistic 1/2/3/4 grid)

Live view today is single-camera (LAN RTSP, + Tailscale remote — see `project_live_streaming_research`).
Add a **holistic live grid** that shows 1, 2, 3 or 4 cameras at once, layout adapting to the count
(1 = full; 2 = split; 3–4 = 2×2). Design notes grounded in this project's constraints:

- **Use the sub-stream (360p/ch1) for tiles, not 2K.** N simultaneous 2K live pulls would swamp the
  2.4 GHz link *and* compete with the recorders (which are the priority — live view must never starve
  recording). Tap-to-expand a tile promotes **that one** camera to 2K full-screen; the grid stays SD.
- **Live pulls come straight from each camera's RTSP (LAN) or via Tailscale (remote)** — the NVR phone
  is not a restream hub (it has no headroom). The app opens one player per visible tile.
- **Cap concurrent tiles** and lazy-load: only visible tiles hold an open stream; a backgrounded/
  scrolled-away tile tears its player down. This bounds bandwidth and battery on the *viewing* phone.
- **Only enabled + reachable cameras get a tile;** a disabled one shows greyed with its last frame, a
  silent/unreachable one shows the alarm state — reusing the three-state model, not a fourth UI path.
- **N=1 = today's single live view**, unchanged; the grid appears only when >1 camera exists.

Open question: max simultaneous SD tiles the *viewer* device + the Wi-Fi comfortably sustain (test,
like we did for the recorders) — and whether remote (Tailscale) caps tiles lower than LAN.

### F3. Per-camera health & storage

- Per-camera **health cards** (status.json is already per camera) — recording mode, wedge state, Wi-Fi
  link quality (the `link_*` fields), battery, last-seen.
- Per-camera **storage breakdown** on the Storage screen + the **quota projection** from B5.

### F4. Enable/disable UI + the "did you unplug it?" prompt

- The `cameras.json` **writer** + a per-camera enable/disable toggle (greyed-out = disabled, history
  still browsable).
- The **temporary-camera workflow** in UI copy: enable → test → **disable first, unplug second**.
- The decided-2026-08-02 **"silent for 6 h → did you unplug it?"** actionable notification
  (`[Disable] [Still installed]`), never auto-disable — a stolen camera must not self-file as "probably
  unplugged."

### F5. Labels

Friendly names ("Entrada", "Patio") in `cameras.json` (shared, because they appear in notification
text), not app-local.

---

## Rollout order (each step shippable, N=1 never regresses)

1. **NVR B4** (per-camera telemetry files) — unblocks clean per-camera app reads; no app change yet.
2. **App F1** (per-camera clip model) — the single enabling change; merged view = today's behavior.
3. **NVR B2 + App F4** (`cameras.json` enable/disable + watchdog integration) — makes a second camera a
   first-class, safely-removable unit. Productizes what this session did by hand.
4. **App F2 + F3 + F3-live** (picker, merged clip view, per-camera health/storage, live multi-view grid).
5. **NVR B1** (upload queue) — do this once ≥2 cameras are a real workload; it's a latency/fairness
   win, not a correctness fix, so it can come after the system already runs N cameras on the scan.
6. **NVR B5** (per-camera retention + quota projection) — before a permanent 3rd camera.
7. **NVR B6 / provisioning docs** — write down now (cheap, already learned this session).

## Open decisions (carry-overs + new)

- **Queue marker format & dead-letter policy** (B1): how many retries before a clip drops to
  `.deadletter` for self-heal to reconcile; how to surface a growing queue (Drive-down) in the app.
- **Merged vs per-camera as the app's default landing view** (F2) — and what N=1 shows.
- **Per-camera retention values** (B5) and whether the app exposes them or they stay NVR-side env.
- **Second-camera default quality** (B5): 360p-by-default with 2K opt-in, given the quota/Wi-Fi walls?
- **Permanent vs temporary intent** (from `MULTICAMERA_PLAN` Q8): a real 4-camera install moves the
  NVR off this phone (see `ROADMAP.md` mini-PC step); 2 cameras are within reach on it.

## Code touchpoints

NVR: `termux/cloud-sync.sh` (fast-lane loop L127+, `classify_sync_err`, retention) · `termux/record-preroll.sh`
(clip finalize/rename → **enqueue marker**; per-camera log envs) · `termux/watchdog.sh` (`CAMS`,
`ensure_session`, add sshd/wake-lock watch) · `~/camN.env`, `cameras.json`.
App: `consumer-app/.../data/DriveClient.kt` (`listClips`, `uploadFavorites`, `fetchCameraHealth`) ·
`consumer-app/.../data/Models.kt` (`Clip`, `ClipRecord`, `CameraHealth`) ·
`consumer-app/.../ui/AppNav.kt` (`CameraStatusCard`, Storage screen, camera picker).
See also `MULTICAMERA_PLAN.md` (requirements, three-state model, capacity) and `ARCHITECTURE.md`.
