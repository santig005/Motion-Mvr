# Termux motion NVR (an old Android phone as a camera recorder)

Turns an old Android phone (tested on an entry-level device) into a motion-triggered recorder for
an RTSP IP camera, using **Termux + ffmpeg + bash**. It records the 2K main stream continuously
into a ring buffer and keeps only the segments around motion — detected cheaply on the 360p
sub-stream — so every clip includes **pre-roll** (the seconds before the trigger).

> Design and rationale: see [`../ARCHITECTURE.md`](../ARCHITECTURE.md).

## Scripts

| Script | What it does |
|---|---|
| `record-preroll.sh` | Main NVR: 2K segmenter (ring buffer) + 360p motion detector + keeper that trims exact clips into `OUT_DIR/YYYY/MM/DD/mt_*.mp4`, writes `metrics.csv` and health `status.json`. |
| `cloud-sync.sh` | Uploads the camera tree to Google Drive with rclone and enforces local/cloud retention. |
| `watchdog.sh` | Self-healing loop: keeps the wake-lock, sshd, and the `cam1`/`cloud` tmux sessions alive. |
| `boot-start-cam.sh` | Robust startup after a reboot (Termux:Boot runs it); launches the watchdog. |
| `clip-metrics.sh` | Offline tool: recompute per-clip motion metrics to compare detector settings. |
| `detect-log.sh` | Offline tool: log motion events only (no recording), to compare two detectors/streams. |

## Requirements on the phone
1. **Termux** (from F-Droid or GitHub, NOT the Play Store) — no Google account required.
2. Packages: `pkg install -y ffmpeg tmux openssh grep`. Optional: `termux-api` (battery reporting).
3. Same Wi-Fi as the camera. **Disable the Wi-Fi random MAC** (use the device MAC); with a random
   MAC Android may return `No route to host` to the camera.

## Install
```bash
# on the phone (Termux)
cp cam.env.example ~/cam1.env         # then edit ~/cam1.env with your real RTSP URL (never commit it)
cp record-preroll.sh cloud-sync.sh watchdog.sh boot-start-cam.sh ~/ && chmod +x ~/*.sh
termux-setup-storage                  # so clips land in shared storage (Gallery/VLC visible)
```

## Run 24/7
```bash
termux-wake-lock                                              # keep Android from sleeping it
tmux new-session -d -s cam1  "cd ~ && CAM_ENV=~/cam1.env exec ./record-preroll.sh"
tmux new-session -d -s cloud "cd ~ && exec ./cloud-sync.sh"
tmux ls                                                       # verify
tail -f ~/logs/cam1.motion.log                               # watch events
```
Or just run `./boot-start-cam.sh`, which starts the watchdog (it launches and revives both sessions).
To stop a session: `tmux kill-session -t cam1` (do NOT `pkill -f record-preroll.sh`).

## Survive reboots (manual, one-time)
Install **Termux:Boot** (F-Droid) and open it once, remove battery optimization for Termux and
Termux:Boot, and keep the phone plugged in. Then copy the boot script into place:
```bash
mkdir -p ~/.termux/boot && cp ~/boot-start-cam.sh ~/.termux/boot/start-cam.sh && chmod +x ~/.termux/boot/start-cam.sh
```

## Administer from a PC (no typing on the phone)
Termux runs `sshd` on port **8022**. From the PC, with an authorized SSH key:
```bash
ssh -i <key> -p 8022 <user>@<phone-ip>          # find <user> with `whoami` in Termux
scp -P 8022 record-preroll.sh <user>@<phone-ip>:~/   # deploy changes
```

## Recalibrate for another scene/camera
1. Capture a frame (`ffmpeg ... -frames:v 1 snap.jpg`) and pick `DET_CROP` over the walkable area.
2. Measure YAVG at rest and in motion (filter `crop,tblend=difference,format=gray,lut,signalstats,metadata=print`)
   and set `YAVG_TH` comfortably between the two.
3. If motion is far/small, **detect on the 2K** (not the sub-stream): resolution matters.
