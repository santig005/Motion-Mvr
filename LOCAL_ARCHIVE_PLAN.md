# Local archive & tiered retention — design + implementation plan

Status: **approved, not started.** Author handoff for a fresh implementation session.

## Motivation

Google Drive is the free 15 GB tier, so cloud retention is short (`CLOUD_KEEP_DAYS=30`). But the
consumer phone (a Pixel) has ~100 GB free — unused space. The user wants to keep a **longer history
on the consumer device**, independent of Drive's 30-day rotation: even after a clip is purged from
Drive, it (or at least its metadata) stays browsable in the app for a user-chosen horizon.

Measured footprint (2026-07-20, real numbers from the NVR):
- **Video:** ~200 MB/day. → 30 d ≈ 6 GB · 90 d ≈ 18 GB · 6 mo ≈ 36 GB (all fit in 100 GB).
- **Thumbnails** (`mt_*.jpg`): ~106 KB each, ~100/day → ~10 MB/day → **~4 GB/year**.
- **Metadata** (`metrics.csv`): 192 KB for ~23 days of full history (since 2026-06-27), ~8 KB/day →
  **~3 MB/year**. **Never purged** — the retention sweep only deletes `*.mp4`/`*.jpg`, so the CSV
  already spans the whole history on both the NVR and Drive. Thumbnails, however, ARE purged with
  the clips (30 d cloud / 7 d local).

Takeaway: the metadata is already permanent and featherweight; thumbnails are cheap; only the video
is heavy — and even that fits for months on the Pixel.

## The 3-tier retention model (target)

1. **Cloud (Drive) — 30 days.** Off-site, redundant backup of recent clips, re-streamable from any
   device. Theft-resilience for recent events. **Unchanged.**
2. **Local video archive (Pixel) — user-chosen horizon (30/60/90 d or custom).** Full video kept on
   the phone (downloaded on Wi-Fi), browsable **even after Drive purges the original**. Favorites
   kept indefinitely.
3. **Metadata + thumbnail history — effectively permanent (years).** A timeline/heatmap of ALL
   motion activity going back months/years, with thumbnail + intensity, **even where the video is
   gone**. Nearly free; the data already exists (`metrics.csv`).

This is the standard prosumer NVR tiering (short cloud clip retention + long local archive), except
the "cloud" tier is the user's own Drive (no subscription) and the long archive lives on a device
they already own.

## The key enabler: a persistent local catalog

Today the app lists clips **from Drive** (`DriveClient.listClips()` → `ClipListCache`, consumed in
`MainViewModel.load()`). So when Drive purges a clip it vanishes from the app, even if it was
downloaded (`OfflineStore` has the file but the list won't show it — it's an orphan).

**Fix:** an on-device catalog (Room DB) that is the UNION of:
- Drive clips (from the listing) — marks `onDrive=true`, stores `driveFileId`.
- Locally-archived clips (downloaded video present).
- Metadata-only entries (parsed from `metrics.csv`, for clips whose video is gone everywhere).

The clip list / timeline reads from this catalog, not directly from Drive. Each clip derives a
**state**:
- ☁️ `CLOUD` — on Drive, not archived locally (stream it).
- ⬇️ `ARCHIVED` — video present locally (play offline; may or may not still be on Drive).
- 📊 `METADATA_ONLY` — metadata (+ maybe thumbnail) only; video gone everywhere (show it happened,
  can't play).

Suggested `ClipRecord`: `name` (PK, `mt_YYYYMMDD_HHMMSS`), `dateKey`, `epoch`, `durationSec`,
`yavgMax`, `intensityLevel`, `sizeBytes`, `onDrive`, `driveFileId?`, `videoLocalPath?`,
`thumbLocalPath?`, `favorite`. Derive date/time from the name (as `Clip` already does).

## Phase A — metadata history (cheap, high ROI, do first)

Goal: a browsable history going back as far as `metrics.csv`, with thumbnail + intensity, **no heavy
video downloads**.

1. **Local catalog (Room).** Add the DB + `ClipRecord` DAO. Seed/refresh it on each load by merging:
   (a) the Drive listing (existing `listClips()`), and (b) **all** rows of `metrics.csv`
   (`DriveClient` already fetches it — `fetchMetrics`/`recentMetrics`; extend to parse the full file).
   A metrics row with no Drive/local presence becomes a `METADATA_ONLY` record.
2. **Thumbnail archive.** When a clip is first seen (still on Drive), download its `mt_*.jpg` to a
   local thumbs dir and record `thumbLocalPath`. Keep thumbnails for a long horizon (e.g. 1 year)
   so the history stays visual after Drive purges the jpg. ~4 GB/year — acceptable; make it a
   setting later.
3. **UI.** The clip list / a "History" view renders from the catalog. `METADATA_ONLY` entries show
   time + intensity + local thumbnail with a "video no disponible / video unavailable" state (not
   tappable to play). Everything else behaves as today.
4. No retention purge of video yet (Phase B owns that). Favorites already special-cased.

Files: new `data/CatalogDb.kt` (Room) + `data/ClipRecord.kt` + `data/ThumbArchive.kt`; modify
`DriveClient.kt` (full metrics parse + thumbnail fetch already exists via `fetchClipThumbnail`),
`MainViewModel.kt` (load from catalog), `ui/AppNav.kt` (list/History view + metadata-only state),
strings (both `values/` and `values-es/`).

## Phase B — full-video local archive + retention horizon

1. **Retention setting.** A "Clips kept on this phone" horizon (30/60/90 d / custom), stored in a
   prefs-backed store, independent of Drive's `CLOUD_KEEP_DAYS`. Surface it in the Storage screen.
2. **Archiver (WorkManager).** Extend the existing auto-download (today it's `OfflineStore` +
   `NewClipsWorker` for today's clips only) to download **all** clips within the horizon, **Wi-Fi
   only**, batched/overnight-friendly. Update `ClipRecord.videoLocalPath`.
3. **Local retention purge.** Delete local videos older than the horizon **except favorites**; keep
   the catalog entry (→ becomes `METADATA_ONLY`) and its thumbnail. Never delete a video that isn't
   safely on Drive AND local unless it's past the horizon (mirror the NVR's fail-safe posture).
4. **Clip-state badges** (☁️/⬇️/📊) in the list + player; the player streams from Drive when `CLOUD`,
   plays the local file when `ARCHIVED`, shows the unavailable state when `METADATA_ONLY`.
5. **Storage screen.** Show archive footprint (video vs thumbnails vs metadata), horizon control,
   and a rough "at this rate, N days ≈ X GB" projection.

Files: `data/OfflineStore.kt` (download-by-horizon + purge), new `notify/ArchiveWorker.kt`, a
retention-settings store, `ui/AppNav.kt` (Storage settings, badges, player states), strings.

## Caveats to honor (tell the user, keep in the UX)

- **Single copy.** Once Drive purges a clip, the Pixel archive is the ONLY copy of that video. Drive
  was the redundancy. Fine for old archive; for anything critical → favorite it (favorites stay on
  Drive, spared from the purge). Consider a gentle note in the retention UI.
- **Wi-Fi + battery.** Auto-downloading everything uses bandwidth + battery; default to Wi-Fi-only,
  ideally while charging / overnight.
- **`metrics.csv` growth** is unbounded (~3 MB/yr — fine for years). Eventually worth an NVR-side
  rotation/cap; out of scope here, just noted.
- The camera is on a weak Wi-Fi link and the NVR is a low-end phone — none of this runs on the NVR;
  it's all consumer-app side, reading from Drive. No NVR changes required for either phase.

## References (current code)

`consumer-app/app/src/main/java/com/famviva/camara/`: `data/DriveClient.kt` (listing, metrics,
thumbnails), `data/OfflineStore.kt` (download/delete, auto-download modes), `data/ClipListCache.kt`
(Drive-listing cache), `data/FavoritesStore.kt`, `data/Models.kt` (`Clip`, `motionIntensityLevel`,
metadata parsing), `MainViewModel.kt` (`load()`), `notify/NewClipsWorker.kt` (15-min poll +
today-only auto-download), `ui/AppNav.kt` (clip list, Storage screen, player). NVR clip layout:
`Cameras/<cam>/YYYY/MM/DD/mt_*.mp4` (+ `.jpg`), `metrics.csv` at the camera root. See
`ARCHITECTURE.md`.
