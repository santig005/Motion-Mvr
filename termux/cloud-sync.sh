#!/data/data/com.termux/files/usr/bin/bash
# cloud-sync.sh — Uploads the whole camera tree to Google Drive (rclone) and applies local and
# cloud retention. A single instance covers every camera (it syncs the recursive root). Decoupled
# from the detector (runs in its own tmux session). If there's no network it retries and does NOT
# delete anything locally until the upload is confirmed.
#
# Expected layout (mirrored locally and on Drive):
#   <CAMERAS_DIR>/cam1/YYYY/MM/DD/*.mp4   ->   <REMOTE>/cam1/YYYY/MM/DD/*.mp4
#   <CAMERAS_DIR>/cam2/YYYY/MM/DD/*.mp4   ->   <REMOTE>/cam2/YYYY/MM/DD/*.mp4   ...
#
# Config via environment variables (or ~/cloud.env):
#   CAMERAS_DIR       local root       (default: /sdcard/Movies/Cameras)
#   RCLONE_REMOTE     rclone root      (default: gdrive:Cameras)
#   LOCAL_KEEP_DAYS   days on phone    (default: 7)
#   CLOUD_KEEP_DAYS   days on Drive    (default: 30)
#   SYNC_INTERVAL     seconds/cycle    (default: 120)
set -u
[ -f "$HOME/cloud.env" ] && . "$HOME/cloud.env"
CAMERAS_DIR="${CAMERAS_DIR:-/sdcard/Movies/Cameras}"
REMOTE="${RCLONE_REMOTE:-gdrive:Cameras}"
LOCAL_KEEP_DAYS="${LOCAL_KEEP_DAYS:-7}"
CLOUD_KEEP_DAYS="${CLOUD_KEEP_DAYS:-30}"
INTERVAL="${SYNC_INTERVAL:-60}"
LOG="${SYNC_LOG:-$HOME/logs/cloud-sync.log}"
mkdir -p "$(dirname "$LOG")" "$CAMERAS_DIR"
log(){ echo "$(date '+%F %T') $*" | tee -a "$LOG"; }

log "=== cloud-sync starts | $CAMERAS_DIR -> $REMOTE | local=${LOCAL_KEEP_DAYS}d cloud=${CLOUD_KEEP_DAYS}d every ${INTERVAL}s ==="
while true; do
  # 1) Upload clips + thumbnails of EVERY camera (recursive). WITHOUT --ignore-existing: rclone
  #    compares size+modtime and RE-UPLOADS anything left partial/corrupt on Drive (self-healing).
  #    --min-age 10s: ignore files modified <10s ago (double safety; the keeper already writes
  #    atomically with .part + rename). The --include filters exclude the .part files.
  if rclone copy "$CAMERAS_DIR" "$REMOTE" --include "*.mp4" --include "*.jpg" --min-age 10s --transfers 2 --stats-one-line >>"$LOG" 2>&1; then
    # 2) Upload/refresh metrics and health status (no ignore-existing so they refresh)
    rclone copy "$CAMERAS_DIR" "$REMOTE" --include "*.csv" --include "*.json" --transfers 2 >>"$LOG" 2>&1
    # 3) LOCAL retention: delete mp4+jpg older than LOCAL_KEEP_DAYS (already confirmed on Drive this cycle)
    n=$(find "$CAMERAS_DIR" \( -name "*.mp4" -o -name "*.jpg" \) -mtime +"$LOCAL_KEEP_DAYS" 2>/dev/null | wc -l)
    if [ "$n" -gt 0 ]; then
      find "$CAMERAS_DIR" \( -name "*.mp4" -o -name "*.jpg" \) -mtime +"$LOCAL_KEEP_DAYS" -delete 2>/dev/null
      log "local retention: deleted $n files > ${LOCAL_KEEP_DAYS}d"
    fi
  else
    log "!! rclone copy failed (no network/Drive?); NOT purging local this cycle"
  fi
  # 4) CLOUD retention: delete on Drive anything older than CLOUD_KEEP_DAYS (mp4 + thumbnails)
  rclone delete "$REMOTE" --include "*.mp4" --include "*.jpg" --min-age "${CLOUD_KEEP_DAYS}d" >>"$LOG" 2>&1 || true
  sleep "$INTERVAL"
done
