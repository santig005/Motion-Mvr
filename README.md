# Motion NVR — a phone-based, motion-triggered camera recorder

Turn an old Android phone into a always-on **NVR** for a cheap RTSP/Wi-Fi camera, and watch the
motion clips from a companion Android app — **no cloud subscription**.

Most consumer Wi-Fi cameras only let you scroll back a day or two unless you pay a monthly fee.
This project records the same live RTSP stream yourself, keeps only the moments with motion (with
a few seconds of pre-roll), archives them to Google Drive, and serves them to a phone app that
plays each clip in place.

It runs on hardware you already own: the recorder is a **spare Android phone** running
[Termux](https://termux.dev/) (ffmpeg + bash), and the viewer is a small **Kotlin / Jetpack
Compose** app. There is an optional [Frigate](https://frigate.video/) path for real AI person
detection on a Raspberry Pi / mini-PC.

> Full design rationale in [`ARCHITECTURE.md`](ARCHITECTURE.md).

---

## What's in here

```
termux/          The NVR: bash + ffmpeg scripts that run on the phone (or any Linux box)
consumer-app/    Android viewer app (Kotlin, Compose, Media3, Google Drive)
frigate/         Optional: Frigate NVR config for AI person/car detection on a Pi/mini-PC
.env.example     Camera credential template (copy to .env, which is gitignored)
```

## How it works (at a glance)

```
  ┌──────────────┐   RTSP 2K (ch0)  ┌────────────────────┐   mp4 + jpg    ┌──────────────┐
  │  Wi-Fi camera│ ───────────────▶ │  Phone NVR (Termux)│ ─────────────▶ │ Google Drive │
  │  (RTSP)      │   RTSP 360p(ch1) │  ffmpeg + bash     │   rclone sync  │              │
  └──────────────┘ ───────────────▶ └────────────────────┘                └──────┬───────┘
                     detect on 360p    ring buffer + keeper                        │ Drive API
                     record on 2K      pre-roll, exact trim                        ▼
                                                                          ┌──────────────────┐
                                                                          │  Android viewer  │
                                                                          │  app (Compose)   │
                                                                          └──────────────────┘
```

1. **Segmenter** records the 2K main stream continuously into a ring buffer (short segments,
   outside the uploaded folder).
2. **Detector** watches the cheap 360p sub-stream for motion (frame-difference on a cropped ROI) —
   about ⅕ the CPU of decoding the 2K.
3. **Keeper** takes the segments overlapping each motion window (so the **pre-roll** is already
   captured), trims a tight clip by profiling the real motion on the 2K, writes a thumbnail and a
   metrics row, and drops it into `Cameras/<cam>/YYYY/MM/DD/mt_*.mp4`.
4. **cloud-sync** mirrors the tree to Google Drive with rclone and enforces local + cloud retention.
5. A **watchdog** keeps everything alive across crashes and reboots.
6. The **app** lists the clips straight from Drive (`drive.readonly`), groups them by day, shows
   thumbnails / motion intensity / camera health, and streams each clip in-app.

## Quick start

### 1. Recorder (phone or Linux box)
See [`termux/README.md`](termux/README.md). In short:
```bash
cp termux/cam.env.example ~/cam1.env    # edit with your RTSP URL (see .env.example)
# install ffmpeg/tmux/openssh, then:
tmux new-session -d -s cam1  "cd ~ && CAM_ENV=~/cam1.env exec ./record-preroll.sh"
tmux new-session -d -s cloud "cd ~ && exec ./cloud-sync.sh"
```

### 2. Viewer app
Open [`consumer-app/`](consumer-app/) in Android Studio and Sync. You'll need a Google OAuth
client (Android) with the Drive API enabled — steps in
[`consumer-app/README.md`](consumer-app/README.md). The app UI supports **English and Spanish**
(switchable in-app).

### 3. (Optional) Frigate for AI detection
See [`frigate/`](frigate/): point it at the same RTSP URLs and run `docker compose up -d`.

## Highlights

- **Pre-roll without wasting CPU** — record the 2K, detect on the 360p; a ring buffer means every
  clip includes the seconds before the trigger with ~0 latency.
- **Content-based tail trimming** — clips end a couple of seconds after the *last real motion*
  measured on the 2K, not on a fixed timer, so there's no dead footage.
- **Atomic writes** — clips/thumbnails are written to `.part` and renamed, so the uploader never
  ships a half-written file.
- **Self-healing** — a watchdog revives the recorder, uploader, wake-lock and sshd; a boot script
  restarts everything after a reboot; camera "stuck" states are handled with staggered backoff.
- **Health surfacing** — the NVR writes a `status.json` (recording fresh? battery? charging?) that
  the app turns into banners and notifications ("camera down — power-cycle it").

## Notes

- The camera is only reachable on the LAN, so the recorder must run on the same network.
- Credentials live only in a local `.env` / `cam.env` (gitignored) — see `.env.example`.
- The pipeline is plain **RTSP**, so it works with any camera that exposes a main + sub stream.
  The specific camera this project was built and validated against is a **FAMVIVA** 2K (AJCloud
  platform) Wi-Fi camera — its two streams are `ch0` (2K, recording) and `ch1` (360p, detection).

## License

MIT — see [`LICENSE`](LICENSE).
