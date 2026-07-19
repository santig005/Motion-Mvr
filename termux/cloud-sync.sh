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
#   HEAL_EVERY        seconds between full-tree self-heal passes (default: 3600). The fast lane
#                     only covers TODAY's folder; this pass re-checks the whole tree (heals
#                     offline spells, partial/corrupt uploads, files outside today).
#   RETENTION_EVERY   seconds between retention sweeps (default: 28800 = 8h, ~3x/day). The
#                     local-purge + Drive `rclone delete` are decoupled from the upload cycle: a
#                     fast upload cadence must NOT run a recursive Drive delete-listing every cycle.
#   LOG_MAX_KB        cap on cloud-sync.log before it's trimmed to its newest half (default: 2048)
#
# QUOTA (why the fast lane is scoped to today): rclone's default shared client_id has a tiny
# per-minute Drive query quota. Re-listing every dated dir of the whole tree each ${SYNC_INTERVAL}s
# kept tripping 403s — and a 403 on a destination LIST makes rclone treat the dir as missing and
# RE-UPLOAD it (one cascade re-sent 230 files / 210 MiB and delayed fresh clips ~17 min on
# 2026-07-16). Scoping the fast lane to today's folder cuts LIST calls ~10x and shrinks a 403's
# blast radius to a single day. No personal client_id required (zero-setup replicability).
set -u
[ -f "$HOME/cloud.env" ] && . "$HOME/cloud.env"
CAMERAS_DIR="${CAMERAS_DIR:-/sdcard/Movies/Cameras}"
REMOTE="${RCLONE_REMOTE:-gdrive:Cameras}"
ROOT_REMOTE="${REMOTE%%:*}:"                      # same remote at its root (for favorites.json the app writes)
LOCAL_KEEP_DAYS="${LOCAL_KEEP_DAYS:-7}"
CLOUD_KEEP_DAYS="${CLOUD_KEEP_DAYS:-30}"
INTERVAL="${SYNC_INTERVAL:-25}"
HEAL_EVERY="${HEAL_EVERY:-3600}"
RETENTION_EVERY="${RETENTION_EVERY:-28800}"
LOG="${SYNC_LOG:-$HOME/logs/cloud-sync.log}"
LOG_MAX_KB="${LOG_MAX_KB:-2048}"
SYNC_STATUS="${SYNC_STATUS:-$CAMERAS_DIR/sync_status.json}"   # per-cycle sync health (depth 1; uploaded by the refresh lane); app infers "sync caído" from a stale 'updated'
EVENTS_LOG="${EVENTS_LOG:-$CAMERAS_DIR/events.jsonl}"         # shared event log (record-preroll also appends); this is the ONLY writer that trims it
EVENTS_MAX_KB="${EVENTS_MAX_KB:-256}"                         # trim events.jsonl to its newest half past this (line boundaries)
mkdir -p "$(dirname "$LOG")" "$CAMERAS_DIR"
log(){ echo "$(date '+%F %T') $*" | tee -a "$LOG"; }

# Global event log at the CAMERAS_DIR root (one short JSON object per line; never URLs/creds). This is
# the ONLY place that trims it (line-boundary, newest half) so a concurrent append from record-preroll
# is safe. cam empty => JSON null (whole-tree events); the *.jsonl refresh include uploads the file.
log_event(){ # $1=cam(empty=null)  $2=svc  $3=ev  $4=msg  $5=dur_s (optional)
  local camf="null" d=""
  [ -n "$1" ] && camf="\"$1\""
  [ -n "${5:-}" ] && d=",\"dur_s\":$5"
  printf '{"ts":%d,"cam":%s,"svc":"%s","ev":"%s"%s,"msg":"%s"}\n' \
    "$(date +%s)" "$camf" "$2" "$3" "$d" "$4" >> "$EVENTS_LOG" 2>/dev/null || true
}

# Trim events.jsonl to its newest half at LINE boundaries (tail -n, never -c, so no half JSON line
# survives). Only cloud-sync trims it, so keeper's concurrent appends can't lose a partial line.
trim_events(){
  local sz lines
  sz=$(stat -c %s "$EVENTS_LOG" 2>/dev/null) || return 0
  [ "$sz" -gt $((EVENTS_MAX_KB * 1024)) ] || return 0
  lines=$(wc -l < "$EVENTS_LOG" 2>/dev/null) || return 0
  [ "${lines:-0}" -gt 1 ] || return 0
  tail -n $((lines / 2)) "$EVENTS_LOG" > "$EVENTS_LOG.tmp" 2>/dev/null && mv -f "$EVENTS_LOG.tmp" "$EVENTS_LOG" 2>/dev/null
}

# Sync health for the app (atomic tmp+mv), written once per cycle. A stale 'updated' => sync is down.
write_sync_status(){
  printf '{"updated":%d,"last_fast_ok":%d,"last_heal_ok":%d,"last_retention_ok":%d,"last_error":"%s","last_error_ts":%d}\n' \
    "$(date +%s)" "$last_fast_ok" "$last_heal_ok" "$last_retention_ok" "$last_error" "$last_error_ts" \
    > "$SYNC_STATUS.tmp" 2>/dev/null && mv -f "$SYNC_STATUS.tmp" "$SYNC_STATUS" 2>/dev/null
}

# The -v upload log (below) makes this grow with real usage instead of staying a fixed-size
# summary; nothing else on the phone rotates logs, so cap it here rather than let it grow forever.
trim_log(){
  local sz
  sz=$(stat -c %s "$LOG" 2>/dev/null) || return 0
  [ "$sz" -gt $((LOG_MAX_KB * 1024)) ] || return 0
  tail -c $((LOG_MAX_KB * 1024 / 2)) "$LOG" > "$LOG.tmp" 2>/dev/null && mv -f "$LOG.tmp" "$LOG" 2>/dev/null
}

log "=== cloud-sync starts | $CAMERAS_DIR -> $REMOTE | local=${LOCAL_KEEP_DAYS}d cloud=${CLOUD_KEEP_DAYS}d | fast lane (today) every ${INTERVAL}s, self-heal every ${HEAL_EVERY}s, retention every ${RETENTION_EVERY}s ==="
last_heal=0
last_retention=0
last_fast_ok=0; last_heal_ok=0; last_retention_ok=0    # epochs of the last successful pass of each lane (for sync_status.json)
last_error=""; last_error_ts=0                          # most recent sync error (short, JSON-safe) + when
while true; do
  now=$(date +%s)
  fast_ok=1                                             # cleared if any fast-lane copy fails this cycle

  # 1) FAST LANE — upload ONLY each camera's TODAY folder (plus yesterday's during the first 30
  #    minutes of the day: a clip whose motion started at 23:59 finalizes past midnight into the
  #    OLD date's folder). One Drive LIST per camera per cycle instead of re-listing every dated
  #    dir of the retention window (see QUOTA note in the header). WITHOUT --ignore-existing:
  #    within the folder rclone still compares size+modtime and re-uploads anything left
  #    partial/corrupt (self-healing). --min-age 10s: ignore files modified <10s ago (double
  #    safety; the keeper already writes atomically with .part + rename).
  # -v: leaves one "Copied (new)" line per uploaded file with rclone's own timestamp. Needed to
  # measure real end-to-end latency (see latency-report.sh).
  today=$(date +%Y/%m/%d)
  extra_day=""
  [ "$(date +%H%M | sed 's/^0*//;s/^$/0/')" -lt 30 ] && extra_day=$(date -d "yesterday" +%Y/%m/%d 2>/dev/null)
  for camdir in "$CAMERAS_DIR"/*/; do
    [ -d "$camdir" ] || continue
    cam=$(basename "$camdir")
    for day in $today $extra_day; do
      [ -d "$camdir$day" ] || continue
      if ! rclone copy "$camdir$day" "$REMOTE/$cam/$day" --include "*.mp4" --include "*.jpg" \
        --min-age 10s --transfers 3 -v --stats-one-line >>"$LOG" 2>&1; then
        log "!! fast lane failed for $cam/$day (no network/Drive?)"
        log_event "$cam" sync error "fast lane $day"
        fast_ok=0; last_error="fast lane $cam/$day"; last_error_ts=$now
      fi
    done
  done
  [ "$fast_ok" = 1 ] && last_fast_ok=$now
  # Health/metrics refresh: status.json, metrics.csv, events.jsonl & sync_status.json live at (or
  # near) each camera's ROOT (depth 1-2 from CAMERAS_DIR); --max-depth keeps this from re-walking the
  # whole dated tree every cycle. *.jsonl added so the global event log rides this same lane.
  rclone copy "$CAMERAS_DIR" "$REMOTE" --include "*.csv" --include "*.json" --include "*.jsonl" --max-depth 2 --transfers 3 >>"$LOG" 2>&1

  # 2) SELF-HEAL (every HEAL_EVERY) — the only FULL-TREE upload pass left. Catches whatever the
  #    fast lane can't see: offline spells, partial/corrupt uploads, files outside today's folder.
  #    --fast-list: one recursive listing call instead of one LIST per directory (quota-friendly).
  if [ "$((now - last_heal))" -ge "$HEAL_EVERY" ]; then
    uploaded_ok=0
    if rclone copy "$CAMERAS_DIR" "$REMOTE" --include "*.mp4" --include "*.jpg" --min-age 10s \
         --transfers 3 --fast-list -v --stats-one-line >>"$LOG" 2>&1; then
      uploaded_ok=1; last_heal_ok=$now
    else
      log "!! self-heal copy failed (no network/Drive?); NOT purging local"
      log_event "" sync error "self-heal copy"
      last_error="self-heal copy"; last_error_ts=$now
    fi
    last_heal=$now

    # 3) RETENTION (every RETENTION_EVERY) — piggybacks on a heal pass so the local purge is
    #    always gated on a JUST-confirmed full-tree upload: nothing is deleted locally unless
    #    Drive is reachable and current.
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
        rclone delete "$REMOTE" --exclude-from "$fav_excl" --include "*.mp4" --include "*.jpg" --min-age "${CLOUD_KEEP_DAYS}d" --fast-list >>"$LOG" 2>&1 || true
      else
        rclone delete "$REMOTE" --include "*.mp4" --include "*.jpg" --min-age "${CLOUD_KEEP_DAYS}d" --fast-list >>"$LOG" 2>&1 || true
      fi
      log "cloud retention sweep (removed Drive files > ${CLOUD_KEEP_DAYS}d)"
      last_retention=$now; last_retention_ok=$now
    fi
  fi

  write_sync_status                                   # once per cycle: 'updated' + per-lane ok epochs + last error
  trim_log
  trim_events
  sleep "$INTERVAL"
done
