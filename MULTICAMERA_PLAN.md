# Multi-camera — design draft and open decisions

Status: **discussion draft, NOT approved and NOT started.** Written 2026-08-02 to capture the
requirements and the trade-offs so the design conversation can resume cold. Nothing here is settled;
the open questions at the end are the point of the document.

## The actual situation

The user owns **four cameras**. Only one is installed — the others are blocked on household
electrical work, not on software. The goal is to be able to run **1, 2, 3 or 4 cameras and turn any
of them on and off at will**.

The critical detail that shapes the whole design: **cameras will be connected temporarily.** The
expected pattern is to plug a second camera in somewhere convenient, spend a few hours exercising
recording / queueing / upload / media handling, and then unplug it again. It is not a permanent
install.

Hard requirement from that: **an unplugged camera must not degrade the system.** No endless NVR retry
loops, no repeated "camera down" alarms, no red banners in the app for a camera the user removed on
purpose.

Proposed mechanism (user's idea): the consumer app writes a file to Google Drive marking camera X as
disabled; the NVR reads it and stops trying. Plus, in the app, the ability to view cameras separately
and all together.

## More already exists than you would expect

Multi-camera was partially designed in from the start:

- `cloud-sync.sh:127` already loops over `"$CAMERAS_DIR"/*/` and uploads `<cam>/YYYY/MM/DD/...` per
  camera. Its header documents `cam1`/`cam2` explicitly. **No change needed for N cameras.**
- `watchdog.sh` already takes `CAMS="${CAMS:-cam1}"` (space-separated), with a per-camera env
  (`~/<cam>.env`) and a per-camera ring (`$RING_BASE/<cam>`). It deliberately does **not**
  auto-discover `cam*.env` (`cam360.env` is a profile, not a camera) — keep that.
- `record-preroll.sh` is already per-camera: `CAM_ENV`, its own `OUT_DIR`, its own `status.json`, its
  own `REC_STATE`. `events.jsonl` and `daily_health.jsonl` are shared at the root and already carry a
  `cam` field.
- The NVR **already classifies an absent camera correctly**: the wedge detector distinguishes "pings
  but RTSP is dead" (wedged) from "does not ping at all" (`🔌 unreachable` = power/network). A
  physically unplugged camera lands in the second bucket already. It just keeps retrying forever.

The real gaps are: nothing reads an enable/disable list, and the app collapses all cameras into one
stream of clips (`DriveClient.listClips()` drops the per-camera path).

## The three-state model (the core idea) — ✅ DECIDED 2026-08-02

> Approved by the user. This section and the "I forgot to disable it" design below are **settled**;
> they are no longer open for redesign. Everything else in this document remains a draft.

Most of the noise the user is worried about comes from conflating two very different situations. The
design should keep three states strictly separate:

| State | Meaning | NVR behaviour | App behaviour |
|---|---|---|---|
| **ENABLED + healthy** | normal operation | records, uploads | normal |
| **ENABLED + silent** | 🚨 **real alarm** — cut cable, theft, power loss | retries, escalates | alerts |
| **DISABLED** | intentionally off | **no session at all**, zero retries, zero alerts | greyed out, history still browsable |

The middle row is the reason this system exists and must never be weakened. The bottom row is what
the user is asking for. The danger to avoid is a design where "disabled" is so easy or so sticky that
a genuinely sabotaged camera gets silently filed as "the user probably unplugged it."

A disabled camera keeps its recorded history: its past clips stay browsable in the app. Disabling
affects the *future*, never the archive.

## The disable mechanism — `cameras.json` on Drive

The user's proposal has an exact working precedent in this codebase: **`favorites.json`**. The app
writes it to the Drive root with the `drive.file` scope and `cloud-sync.sh:184` reads it back with
`rclone cat` to protect favourites from the purge. The same round trip, already proven in production.

Proposed shape, at the Drive root next to `favorites.json`:

```json
{
  "updated": 1785680000,
  "cameras": {
    "cam1": { "enabled": true,  "label": "Entrada" },
    "cam2": { "enabled": false, "label": "Patio" }
  }
}
```

Ownership split (proposed, open to debate):
- **The NVR owns existence.** Only it has the RTSP credentials, so a camera exists when `~/camN.env`
  exists. The app can never invent a camera.
- **The app owns enabled/disabled.** It writes `cameras.json`; the NVR reads it.

Propagation latency rides the existing sync lanes (~25 s fast lane, hourly self-heal), so a disable
takes effect in about a minute. Good enough — but see the workflow below.

**Fail-safe direction matters.** If `cameras.json` is missing, unreadable or malformed, the NVR must
treat every configured camera as **enabled**. A parse failure must never silently disable
surveillance. Disabling is an explicit, positive act.

## Intended workflow for a temporary camera

The clean sequence for a test session — worth surfacing in the app's UI copy:

1. Plug the camera in; create `~/cam2.env` on the NVR (one-time, needs its RTSP URL).
2. **Enable `cam2` in the app** → within ~1 min the NVR starts recording it.
3. Run the tests (recording, queueing, upload, media handling).
4. **Disable `cam2` in the app** → the NVR tears its session down cleanly.
5. *Then* physically unplug it.

Doing step 4 before step 5 is the whole trick: disable first, unplug second.

### The "I forgot to disable it" problem — ✅ DECIDED 2026-08-02

Step 4 will get skipped sometimes. The system must degrade gracefully without going deaf.

Design (approved): keep the normal alarm for the first window (a cut cable must still scream), but if
an enabled camera stays silent for **`SILENT_ASK_HOURS` (default 6 h, tunable)**, stop repeating the
alarm and replace it with **one actionable notification**:

> `cam2` has been silent for 6 h — did you unplug it?  → [ Disable ] [ It is still installed ]

Either answer stops the repetition. "Still installed" keeps it enabled and escalates properly (that
is now a real incident). What the system must **not** do is auto-disable on its own: that is exactly
how a stolen camera would go quiet forever.

## Changes needed

### NVR
- Read `cameras.json` (rclone cat, same as favourites) into the effective `CAMS` list. Cache it
  locally so a Drive outage does not change behaviour.
- `watchdog.sh`: only `ensure_session` for **enabled** cameras; actively `tmux kill-session` for ones
  that became disabled. Nothing else should keep them alive.
- Do not touch `cloud-sync.sh` — it already walks whatever camera directories exist.
- Decide what a disabled camera's `status.json` should say, so the app can tell "disabled" from
  "stale/dead" (see open question 2).

### App
- `DriveClient.listClips()`: request `parents` (or the path) in the Drive `fields` query and derive
  the camera; add `camera` to `Clip`/`ClipRecord`. Today every `mt_` is pooled together.
- Camera picker / filter: per-camera view **and** a merged "All cameras" view (the user wants both).
- Per-camera health cards — `status.json` is already per camera, and strings already interpolate
  `h.camera`.
- Per-camera storage breakdown in the Storage screen.
- The `cameras.json` writer + the enable/disable UI, and the "did you unplug it?" prompt.

## Hard capacity limits — read before adding camera #2

This is the part that constrains the ambition, and it is better known now than discovered later. All
numbers come from real measurements on the production phone (see
`_private/battery-investigation-2026-07-15.md`).

**The NVR phone is a Galaxy A03 and it is already near its limit with ONE camera.**

- Per camera: 2K recorder ffmpeg ~10–12 % CPU (RSS ~62 MB) + 360p detector ~2.5 % (RSS ~57 MB).
- CPU looks survivable for two. **RAM does not.** The phone runs at ~90 MB free with ~1.4 GB already
  in zram swap and a sustained load average around 5. A second camera adds ~120 MB of resident
  memory it does not have — it goes straight into more swap thrashing, which is precisely the
  condition that has historically stalled the segmenter.

**RAM is the binding constraint, not CPU.** Realistic ceiling: **2 cameras**, and the second one may
need to record the 360p sub-stream rather than 2K. Four cameras on this hardware is not realistic.

**Wi-Fi:** each 2K stream is ~2–4 Mbps on a 2.4 GHz link measured between 19 and 58 Mbps, which has
already caused flapping at one camera.

**Google Drive free tier (15 GB) is the hardest wall of all.** At the measured ~200 MB/day/camera and
the current 30-day cloud retention:

| Cameras | 30-day cloud footprint | Fits in 15 GB? |
|---|---|---|
| 1 | ~6 GB | ✅ (current) |
| 2 | ~12 GB | ⚠️ tight |
| 3 | ~18 GB | ❌ |
| 4 | ~24 GB | ❌ |

So **three or four cameras forces a decision**: shorten cloud retention to ~7–10 days, or pay for
Drive storage. Local storage is not the issue (37 GB free on the NVR; the Pixel archive has ~100 GB).

**Consequence for the roadmap:** the moment a genuine 3–4 camera setup is wanted, the NVR phone is no
longer the right host. That — not electricity cost — is the argument that would justify the mini PC
in `ROADMAP.md` step 5. Testing camera #2 temporarily is well within reach; a permanent four-camera
install is not, on this hardware.

## Open questions (the actual agenda for the next conversation)

1. **Source of truth.** App-writes-Drive / NVR-reads, as proposed? What should happen if Drive is
   unreachable for a long time — does the NVR keep the last cached list forever?
2. **Disabled camera's `status.json`:** publish an explicit `"disabled": true`, or stop publishing
   entirely? Explicit is friendlier to the app but means the NVR still runs something for it.
3. ~~Auto-disable prompt design~~ — **settled 2026-08-02** (three-state model + never auto-disable +
   the actionable prompt). Only `SILENT_ASK_HOURS` is left to tune, default 6 h; real usage during
   the camera-2 test should confirm or move it.
4. **Labels.** Friendly names ("Entrada", "Patio") — stored in `cameras.json`, or app-local only?
   They affect notification text, so probably shared.
5. **Default view.** Does the app open on the merged stream or on a single camera? What happens to
   the existing single-camera UX when N = 1 (it should not get worse)?
6. **Cloud retention when N > 2.** Global shortening, or per-camera retention (e.g. the main entrance
   keeps 30 days, the test camera keeps 3)?
7. **Sub-stream default.** Should camera #2 record 360p by default given the RAM ceiling, with 2K as
   an explicit opt-in?
8. **Scope check:** is the near-term goal only "test a second camera for a few hours", or a real
   permanent two-camera install? The answers above change materially between those two.

## Code references

`termux/cloud-sync.sh` (per-camera loop L127, `favorites.json` read L184, `ROOT_REMOTE` L38) ·
`termux/watchdog.sh` (`CAMS` L18, `ensure_session` L33) · `termux/record-preroll.sh` (per-camera env,
`status.json`, wedge/unreachable classifier) · `consumer-app/.../data/DriveClient.kt`
(`listClips`, `uploadFavorites`, `fetchCameraHealth`) · `consumer-app/.../data/Models.kt` (`Clip`,
`CameraHealth`) · `consumer-app/.../ui/AppNav.kt` (`CameraStatusCard`, Storage screen).
Drive layout: `Cameras/<cam>/YYYY/MM/DD/mt_*.mp4` — see `ARCHITECTURE.md`.
