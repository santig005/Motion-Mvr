#!/data/data/com.termux/files/usr/bin/bash
# cloud-sync.sh — Uploads the whole camera tree to Google Drive (rclone) and applies local and
# cloud retention. A single instance covers every camera (it syncs the recursive root). Decoupled
# from the detector (runs in its own tmux session). If there's no network it retries and does NOT
# delete anything locally until the upload is confirmed.
#
# Favourites: the app writes a favorites.json (list of mt_* basenames) at the Drive root; the cloud
# retention sweep excludes those clips so a starred clip's Drive original survives the purge.
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
#   SYNC_INTERVAL     seconds/cycle    (default: 25) — how fast a new clip lands on Drive
#   RETENTION_EVERY   seconds between retention sweeps (default: 28800 = 8h, ~3x/day). The
#                     local-purge + Drive `rclone delete` are decoupled from the upload cycle: a
#                     fast upload cadence must NOT run a recursive Drive delete-listing every cycle.
#   LOG_MAX_KB        cap on cloud-sync.log before it's trimmed to its newest half (default: 2048)
set -u
[ -f "$HOME/cloud.env" ] && . "$HOME/cloud.env"
CAMERAS_DIR="${CAMERAS_DIR:-/sdcard/Movies/Cameras}"
REMOTE="${RCLONE_REMOTE:-gdrive:Cameras}"
ROOT_REMOTE="${REMOTE%%:*}:"                      # same remote at its root (for favorites.json the app writes)
LOCAL_KEEP_DAYS="${LOCAL_KEEP_DAYS:-7}"
CLOUD_KEEP_DAYS="${CLOUD_KEEP_DAYS:-30}"
INTERVAL="${SYNC_INTERVAL:-25}"
RETENTION_EVERY="${RETENTION_EVERY:-28800}"
LOG="${SYNC_LOG:-$HOME/logs/cloud-sync.log}"
LOG_MAX_KB="${LOG_MAX_KB:-2048}"
mkdir -p "$(dirname "$LOG")" "$CAMERAS_DIR"
log(){ echo "$(date '+%F %T') $*" | tee -a "$LOG"; }

# The -v upload log (below) makes this grow with real usage instead of staying a fixed-size
# summary; nothing else on the phone rotates logs, so cap it here rather than let it grow forever.
trim_log(){
  local sz
  sz=$(stat -c %s "$LOG" 2>/dev/null) || return 0
  [ "$sz" -gt $((LOG_MAX_KB * 1024)) ] || return 0
  tail -c $((LOG_MAX_KB * 1024 / 2)) "$LOG" > "$LOG.tmp" 2>/dev/null && mv -f "$LOG.tmp" "$LOG" 2>/dev/null
}

log "=== cloud-sync starts | $CAMERAS_DIR -> $REMOTE | local=${LOCAL_KEEP_DAYS}d cloud=${CLOUD_KEEP_DAYS}d | upload every ${INTERVAL}s, retention every ${RETENTION_EVERY}s ==="
last_retention=0
while true; do
  now=$(date +%s)
  uploaded_ok=0
  # 1) Upload clips + thumbnails of EVERY camera (recursive). WITHOUT --ignore-existing: rclone
  #    compares size+modtime and RE-UPLOADS anything left partial/corrupt on Drive (self-healing).
  #    --min-age 10s: ignore files modified <10s ago (double safety; the keeper already writes
  #    atomically with .part + rename). The --include filters exclude the .part files.
  # -v: leaves one "Copied (new)" line per uploaded file with rclone's own timestamp. Needed to
  # measure real end-to-end latency (see latency-report.sh); negligible extra cost (a print, not a
  # process) since transfers are already happening.
  if rclone copy "$CAMERAS_DIR" "$REMOTE" --include "*.mp4" --include "*.jpg" --min-age 10s --transfers 3 -v --stats-one-line >>"$LOG" 2>&1; then
    # Upload/refresh metrics and health status (no ignore-existing so they refresh)
    rclone copy "$CAMERAS_DIR" "$REMOTE" --include "*.csv" --include "*.json" --transfers 3 >>"$LOG" 2>&1
    uploaded_ok=1
  else
    log "!! rclone copy failed (no network/Drive?); NOT purging local this cycle"
  fi

  # 2) RETENTION SWEEP — decoupled from the upload cycle so a fast upload cadence doesn't run a
  #    recursive Drive delete-listing every ${INTERVAL}s. Runs ~every RETENTION_EVERY (a few times
  #    a day). Local purge only after a confirmed upload this cycle (Drive reachable + current).
  if [ "$((now - last_retention))" -ge "$RETENTION_EVERY" ]; then
    if [ "$uploaded_ok" = 1 ]; then
      n=$(find "$CAMERAS_DIR" \( -name "*.mp4" -o -name "*.jpg" \) -mtime +"$LOCAL_KEEP_DAYS" 2>/dev/null | wc -l)
      if [ "$n" -gt 0 ]; then
        find "$CAMERAS_DIR" \( -name "*.mp4" -o -name "*.jpg" \) -mtime +"$LOCAL_KEEP_DAYS" -delete 2>/dev/null
        log "local retention: deleted $n files > ${LOCAL_KEEP_DAYS}d"
      fi
    fi
    # Delete on Drive anything older than CLOUD_KEEP_DAYS (mp4 + thumbnails) — EXCEPT favourites.
    # The app publishes favorites.json (a JSON list of mt_* basenames) at the Drive root; honour it
    # so a starred clip's ORIGINAL survives the purge (re-streamable/re-downloadable on any device).
    # Exclude rules go first (first match wins) so a favourite is spared before the *.mp4/*.jpg
    # includes select it. A missing/empty/unreadable marker => the normal purge (fail-open).
    fav_excl=""
    favs=$(rclone cat "${ROOT_REMOTE}favorites.json" 2>/dev/null | grep -oE 'mt_[0-9]{8}_[0-9]{6}' | sort -u)
    if [ -n "$favs" ]; then
      fav_excl="$HOME/.fav_excludes"
      printf '%s.*\n' $favs > "$fav_excl" 2>/dev/null    # one "mt_YYYYMMDD_HHMMSS.*" rule per favourite (mp4 + jpg)
      log "cloud retention: sparing $(printf '%s\n' "$favs" | grep -c .) favourite(s) from the >${CLOUD_KEEP_DAYS}d purge"
    fi
    if [ -n "$fav_excl" ]; then
      rclone delete "$REMOTE" --exclude-from "$fav_excl" --include "*.mp4" --include "*.jpg" --min-age "${CLOUD_KEEP_DAYS}d" >>"$LOG" 2>&1 || true
    else
      rclone delete "$REMOTE" --include "*.mp4" --include "*.jpg" --min-age "${CLOUD_KEEP_DAYS}d" >>"$LOG" 2>&1 || true
    fi
    log "cloud retention sweep (removed Drive files > ${CLOUD_KEEP_DAYS}d)"
    last_retention=$now
  fi

  trim_log
  sleep "$INTERVAL"
done
