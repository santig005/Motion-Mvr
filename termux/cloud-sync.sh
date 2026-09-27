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
#   QUOTA_EVERY       seconds between `rclone about` quota probes (default: 900 = 15 min). One cheap
#                     API call; 15 min so a threshold crossing reaches the app within 30 min (the
#                     app's own health worker runs every 15 min).
#   LOG_MAX_KB        cap on cloud-sync.log before it's trimmed to its newest half (default: 2048)
#
# TRASH (2026-08-14): the Drive backend defaults to --drive-use-trash=true, i.e. `rclone delete`
# only MOVES files to the trash — and trashed bytes still count against the quota. The 30-day sweep
# was running correctly for weeks while freeing zero bytes: 4.8 GiB of purged clips piled up in the
# trash until the 15 GiB account filled and uploads died mid-morning with storageQuotaExceeded.
# Both deletes below pass --drive-use-trash=false so a purge actually reclaims space.
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
QUOTA_EVERY="${QUOTA_EVERY:-900}"
LOG="${SYNC_LOG:-$HOME/logs/cloud-sync.log}"
LOG_MAX_KB="${LOG_MAX_KB:-2048}"
SYNC_STATUS="${SYNC_STATUS:-$CAMERAS_DIR/sync_status.json}"   # per-cycle sync health (depth 1; uploaded by the refresh lane); app infers "sync caído" from a stale 'updated'
EVENTS_LOG="${EVENTS_LOG:-$CAMERAS_DIR/events.jsonl}"         # SYSTEM event log (sync/quota; only cloud-sync writes it). Per-camera logs live at <cam>/events.jsonl
UPLOAD_QUEUE="${UPLOAD_QUEUE:-$HOME/.upload_queue}"        # FIFO of finalized clips; record-preroll.sh enqueue_upload is the producer
QUEUE_BATCH="${QUEUE_BATCH:-5}"                             # markers per rclone call (~2 min of 2K clips at the ~200 KiB/s uplink measured 2026-09-26)
QUEUE_MAX_BATCHES="${QUEUE_MAX_BATCHES:-6}"                 # per cycle, so the refresh/heal lanes still get their turn under a backlog
QUEUE_CYCLE_SECS="${QUEUE_CYCLE_SECS:-120}"                 # no NEW batch starts after this long in one drain: a backlog must not freeze the loop
QUEUE_MAX_TRIES="${QUEUE_MAX_TRIES:-5}"                     # NON-transient failures before a marker is set aside (the scans still upload the file)
FAST_EVERY="${FAST_EVERY:-300}"                             # today-folder scan, now only the backstop behind the queue
EVENTS_MAX_KB="${EVENTS_MAX_KB:-256}"                         # trim each events.jsonl to its newest half past this (line boundaries)
mkdir -p "$(dirname "$LOG")" "$CAMERAS_DIR"
log(){ echo "$(date '+%F %T') $*" | tee -a "$LOG"; }

# System event log at the CAMERAS_DIR root (one short JSON object per line; never URLs/creds). Only
# cloud-sync writes it; camera events go to each camera's own <cam>/events.jsonl (keeper + watchdog).
# cam empty => JSON null (whole-tree events); the *.jsonl refresh include uploads the file.
log_event(){ # $1=cam(empty=null)  $2=svc  $3=ev  $4=msg  $5=dur_s (optional)
  local camf="null" d=""
  [ -n "$1" ] && camf="\"$1\""
  [ -n "${5:-}" ] && d=",\"dur_s\":$5"
  printf '{"ts":%d,"cam":%s,"svc":"%s","ev":"%s"%s,"msg":"%s"}\n' \
    "$(date +%s)" "$camf" "$2" "$3" "$d" "$4" >> "$EVENTS_LOG" 2>/dev/null || true
}

# UPLOAD QUEUE (multi-camera B1, 2026-09-26). Each finalized clip leaves a marker in $UPLOAD_QUEUE named
# <finalize-ns>.<camera>.<clip> whose content is the mp4's path. Draining oldest-name-first uploads clips
# in the order they FINISHED, across every camera: first come, first served. Before this, the fast lane
# walked camera folders one after another, so a fresh clip of one camera waited behind the other
# camera's whole folder.
#   - Batches of QUEUE_BATCH through ONE rclone call (--files-from-raw + --no-traverse: per-file checks,
#     no folder listings -> gentle on the Drive query quota that caused the 2026-07-16 403 cascade).
#   - Success removes the batch's markers. Failure keeps them; only a NON-transient error (not network /
#     rate limit / auth / Drive full) counts a try, and after QUEUE_MAX_TRIES the marker moves to
#     .deadletter/ so one bad file cannot block the head of the queue forever. A Drive outage never
#     dead-letters anything.
#   - The queue is an optimisation, never the only record: the FAST_EVERY scan and the hourly self-heal
#     still upload anything a marker missed (crash before enqueue, dead-lettered, pre-queue keeper).
# Returns 0 when the queue ended empty (or capped) without error, 1 when a batch failed.
queue_names(){ ls -1 "$UPLOAD_QUEUE" 2>/dev/null | grep -v '^\.' | sort; }
drain_queue(){
  local q="$UPLOAD_QUEUE" list err batch m f rel n=0 rc reason tries t0
  [ -d "$q" ] || return 0
  list="$q/.batch.$$"; err="$q/.err.$$"; t0=$(date +%s)
  while [ "$n" -lt "$QUEUE_MAX_BATCHES" ] && [ "$(( $(date +%s) - t0 ))" -lt "$QUEUE_CYCLE_SECS" ]; do
    batch=$(queue_names | head -n "$QUEUE_BATCH")
    [ -n "$batch" ] || { rm -f "$list" "$err"; return 0; }
    n=$((n + 1))                                             # counts stale-only batches too, so the loop is always bounded
    : > "$list"
    for m in $batch; do
      f=$(head -n 1 "$q/$m" 2>/dev/null)
      case "$f" in
        "$CAMERAS_DIR"/*.mp4) ;;
        *) log "queue: dropping malformed marker $m"; rm -f "$q/$m"; continue ;;
      esac
      [ -f "$f" ] || { rm -f "$q/$m"; continue; }          # purged or never finished: nothing to upload
      rel=${f#"$CAMERAS_DIR"/}
      printf '%s\n' "$rel" >> "$list"
      [ -f "${f%.mp4}.jpg" ] && printf '%s\n' "${rel%.mp4}.jpg" >> "$list"
    done
    [ -s "$list" ] || continue                               # a batch of stale markers: take the next one
    rclone copy "$CAMERAS_DIR" "$REMOTE" --files-from-raw "$list" --no-traverse \
      --check-first --order-by modtime,ascending --transfers 3 -v --stats-one-line >"$err" 2>&1; rc=$?
    cat "$err" >>"$LOG" 2>/dev/null                         # keeps the "Copied (new)" lines latency-report needs
    if [ "$rc" -eq 0 ]; then
      for m in $batch; do rm -f "$q/$m"; done
      # Uploads ARE flowing: say so now, not only at the end of the cycle. A long drain used to leave
      # sync_status.json frozen, which the keeper and the app read as "sync down" after 15 min.
      last_fast_ok=$(date +%s); write_sync_status
    else
      reason=$(classify_sync_err "$err")
      log "!! upload queue batch failed ($reason); $(printf '%s\n' "$batch" | grep -c .) clip(s) stay queued"
      log_event "" sync error "upload queue: $reason"
      last_error="$reason"; last_error_ts=$(date +%s)
      if [ "$reason" = error ]; then
        mkdir -p "$q/.deadletter" 2>/dev/null
        for m in $batch; do
          [ -f "$q/$m" ] || continue
          echo try >> "$q/$m"
          tries=$(( $(wc -l < "$q/$m") - 1 ))
          [ "$tries" -ge "$QUEUE_MAX_TRIES" ] && mv -f "$q/$m" "$q/.deadletter/$m" \
            && log "queue: set aside $m after $tries failed tries (the scans will still upload it)"
        done
      fi
      rm -f "$list" "$err"; return 1
    fi
  done
  rm -f "$list" "$err"; return 0
}

# Filter rules for the directory scans: every clip that still has a marker is EXCLUDED, so a scan only
# ever uploads what the queue does not know about. Otherwise a scan grabs queued clips out of FIFO
# order and, while it chews through a backlog, the queue waits behind it (seen on the first deploy,
# 2026-09-26). Excludes come first (first match wins), then the usual mp4/jpg includes.
scan_filter(){ # $1 = rules file to write
  { queue_names | sed 's/^[0-9]*\.[^.]*\.//; s/^/- /; s/$/.*/'
    printf '+ *.mp4\n+ *.jpg\n- **\n'; } > "$1" 2>/dev/null
}

# BACKSTOP AUDIT. A camera-tagged clip (mt_<date>_<time>_<camera>.mp4) always comes from a keeper that
# enqueues it, so when a directory scan has to upload one that has NO marker in the queue, the queue
# missed it (keeper died between finalize and enqueue, marker lost, ...). Those are logged, counted in
# sync_status.json and recorded as a `sync backstop` event, so a leak in the queue is visible instead
# of silently papered over. NOT flagged: untagged names (pre-queue keepers) and clips whose marker is
# still queued (the scan merely got there a few seconds before the queue did).
backstop_total=0
backstop_audit(){ # $1 = rclone -v output of a scan  $2 = lane name
  local names b missed="" n=0 where
  names=$(grep -oE 'mt_[0-9]{8}_[0-9]{6}_[A-Za-z0-9-]+\.mp4: Copied' "$1" 2>/dev/null | sed 's/: Copied$//' | sort -u)
  for b in $names; do
    b=${b%.mp4}
    ls -1 "$UPLOAD_QUEUE" 2>/dev/null | grep -q "\.$b\$" && continue
    where=""; ls -1 "$UPLOAD_QUEUE/.deadletter" 2>/dev/null | grep -q "\.$b\$" && where=" (set aside)"
    log "⚠ backstop ($2) uploaded $b$where: it was NOT in the upload queue"
    missed="$missed${missed:+ }$b$where"; n=$((n + 1))
  done
  [ "$n" -gt 0 ] || return 0
  backstop_total=$((backstop_total + n))
  log_event "" sync backstop "$2 uploaded $n clip(s) missing from the queue: $(printf '%s' "$missed" | cut -c1-200)"
}

# Queue depth + age of its oldest marker, for sync_status.json (a growing queue = uploads falling behind).
queue_stats(){ # sets queue_len, queue_oldest_s
  local names first ns
  names=$(queue_names); queue_len=$(printf '%s\n' "$names" | grep -c .)
  first=$(printf '%s\n' "$names" | head -n 1); ns=${first%%.*}
  if [ -n "$first" ] && [ "$ns" -gt 0 ] 2>/dev/null; then
    queue_oldest_s=$(( $(date +%s) - ns / 1000000000 ))
  else
    queue_oldest_s=0
  fi
}

# Clip base names out of the app's favorites.json (stdin), one per line, deduplicated.
fav_basenames(){ grep -oE 'mt_[0-9]{8}_[0-9]{6}(_[A-Za-z0-9-]+)?' | sort -u; }

# Trim an event log to its newest half at LINE boundaries (tail -n, never -c, so no half JSON line
# survives). cloud-sync is the ONLY trimmer of every events.jsonl — the system one AND each camera's —
# so the keepers and the watchdog only ever append, and never race another rewriter.
trim_events_file(){ # $1 = events file
  local f="$1" sz lines
  sz=$(stat -c %s "$f" 2>/dev/null) || return 0
  [ "$sz" -gt $((EVENTS_MAX_KB * 1024)) ] || return 0
  lines=$(wc -l < "$f" 2>/dev/null) || return 0
  [ "${lines:-0}" -gt 1 ] || return 0
  tail -n $((lines / 2)) "$f" > "$f.tmp" 2>/dev/null && mv -f "$f.tmp" "$f" 2>/dev/null
}
trim_events(){
  local f
  for f in "$EVENTS_LOG" "$CAMERAS_DIR"/*/events.jsonl; do
    [ -f "$f" ] && trim_events_file "$f"
  done
  return 0
}

# Sync health for the app (atomic tmp+mv), written once per cycle. A stale 'updated' => sync is down.
# drive_* are the last successful quota probe (see probe_quota); drive_pct = -1 until the first one
# lands, so the app can tell "not measured yet" from "measured at 0%".
write_sync_status(){
  local queue_len queue_oldest_s; queue_stats
  printf '{"updated":%d,"last_fast_ok":%d,"last_heal_ok":%d,"last_retention_ok":%d,"last_error":"%s","last_error_ts":%d,"drive_pct":%d,"drive_free_mb":%d,"drive_total_mb":%d,"drive_checked":%d,"queue_len":%d,"queue_oldest_s":%d,"backstop_total":%d}\n' \
    "$(date +%s)" "$last_fast_ok" "$last_heal_ok" "$last_retention_ok" "$last_error" "$last_error_ts" \
    "$drive_pct" "$drive_free_mb" "$drive_total_mb" "$drive_checked" "$queue_len" "$queue_oldest_s" "$backstop_total" \
    > "$SYNC_STATUS.tmp" 2>/dev/null && mv -f "$SYNC_STATUS.tmp" "$SYNC_STATUS" 2>/dev/null
}

# Drive quota probe (one `rclone about --json` call every QUOTA_EVERY). The point is to warn BEFORE
# uploads start failing: on 2026-08-14 the only signal that the account was full was
# storageQuotaExceeded on an upload that had ALREADY been lost, ~5 h before anyone noticed.
# Crossing UP through 90/95/100% logs one event per bucket; the bucket resets when usage drops back
# below a step, so a purge that frees space re-arms the warning instead of going quiet forever.
probe_quota(){
  local out total used free
  out=$(rclone about "$ROOT_REMOTE" --json 2>/dev/null) || return 1
  # No jq on the phone: pull the bare integers out of rclone's pretty-printed JSON.
  total=$(printf '%s' "$out" | tr -d ' \t' | sed -n 's/^"total":\([0-9]*\),*$/\1/p' | head -1)
  used=$(printf  '%s' "$out" | tr -d ' \t' | sed -n 's/^"used":\([0-9]*\),*$/\1/p'  | head -1)
  free=$(printf  '%s' "$out" | tr -d ' \t' | sed -n 's/^"free":\([0-9]*\),*$/\1/p'  | head -1)
  [ -n "${total:-}" ] && [ -n "${used:-}" ] && [ "${total:-0}" -gt 0 ] 2>/dev/null || return 1
  drive_total_mb=$((total / 1048576))
  drive_free_mb=$(( ${free:-0} / 1048576 ))
  drive_pct=$(( used * 100 / total ))
  drive_checked=$(date +%s)

  # Highest threshold currently crossed (0 = below 90%).
  local bucket=0
  [ "$drive_pct" -ge 90 ]  && bucket=90
  [ "$drive_pct" -ge 95 ]  && bucket=95
  [ "$drive_pct" -ge 100 ] && bucket=100
  if [ "$bucket" -gt "$quota_bucket" ]; then
    log "⚠️ Drive at ${drive_pct}% (${drive_free_mb} MB free of ${drive_total_mb} MB) — crossed the ${bucket}% mark"
    log_event "" sync quota "drive ${bucket}% (${drive_pct}% used, ${drive_free_mb} MB free)"
  elif [ "$bucket" -lt "$quota_bucket" ]; then
    log "✅ Drive back down to ${drive_pct}% (${drive_free_mb} MB free) — ${quota_bucket}% warning re-armed"
  fi
  quota_bucket=$bucket
  return 0
}

# Classify the most recent rclone failure from its captured output into a short reason CODE the app
# maps to a clear message ("Drive full", "no connection", ...). The generic "fast lane <folder>"
# string told the user WHERE it failed but not WHY (a full Drive read identically to a dropped link).
# Fail-open to "error" for anything unrecognised.
classify_sync_err(){ # $1 = file with the failed rclone's captured output
  local t; t=$(tail -c 6000 "$1" 2>/dev/null)
  case "$t" in
    *storageQuotaExceeded*)                          echo storage_full ;;
    *rateLimitExceeded*|*userRateLimitExceeded*|*"Quota exceeded"*) echo rate_limit ;;
    *invalid_grant*|*"Error 401"*|*"token has been expired"*|*unauthorized*) echo auth ;;
    *"no such host"*|*"dial tcp"*|*"connection refused"*|*"i/o timeout"*|*"network is unreachable"*|*"couldn't connect"*) echo network ;;
    *)                                               echo error ;;
  esac
}

# The -v upload log (below) makes this grow with real usage instead of staying a fixed-size
# summary; nothing else on the phone rotates logs, so cap it here rather than let it grow forever.
trim_log(){
  local sz
  sz=$(stat -c %s "$LOG" 2>/dev/null) || return 0
  [ "$sz" -gt $((LOG_MAX_KB * 1024)) ] || return 0
  tail -c $((LOG_MAX_KB * 1024 / 2)) "$LOG" > "$LOG.tmp" 2>/dev/null && mv -f "$LOG.tmp" "$LOG" 2>/dev/null
}

# Test hook: `CLOUD_SYNC_LIB=1 . cloud-sync.sh` loads the config and functions without entering the
# sync loop. Must stay the last line before the loop.
[ "${CLOUD_SYNC_LIB:-0}" = 1 ] && return 0

log "=== cloud-sync starts | $CAMERAS_DIR -> $REMOTE | local=${LOCAL_KEEP_DAYS}d cloud=${CLOUD_KEEP_DAYS}d | fast lane (today) every ${INTERVAL}s, self-heal every ${HEAL_EVERY}s, retention every ${RETENTION_EVERY}s ==="
# First full-tree heal (and the retention that rides on it) 5 min after start, not in the very first
# cycle: on a restart the queue should get going first; the scan + queue cover the gap meanwhile.
last_heal=$(( $(date +%s) - HEAL_EVERY + 300 ))
last_fast=0
last_retention=0
last_quota=0
last_fast_ok=0; last_heal_ok=0; last_retention_ok=0    # epochs of the last successful pass of each lane (for sync_status.json)
last_error=""; last_error_ts=0                          # most recent sync error (short, JSON-safe) + when
drive_pct=-1; drive_free_mb=0; drive_total_mb=0; drive_checked=0   # last quota probe (-1 = never measured)
quota_bucket=0                                          # highest 90/95/100 step already warned about
while true; do
  now=$(date +%s)
  fast_ok=1                                             # cleared if the queue or the fast scan fails this cycle
  tmperr="$LOG.err.$$"                                # this cycle's captured rclone output (for reason classification)

  # 0) UPLOAD QUEUE (every cycle) — every camera's finalized clips, oldest-finished first. See drain_queue.
  drain_queue || fast_ok=0

  # 1) FAST SCAN (every FAST_EVERY; was every cycle until the queue existed) — upload ONLY each camera's TODAY folder (plus yesterday's during the first 30
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
  if [ "$((now - last_fast))" -ge "$FAST_EVERY" ]; then
    for camdir in "$CAMERAS_DIR"/*/; do
      [ -d "$camdir" ] || continue
      cam=$(basename "$camdir")
      for day in $today $extra_day; do
        [ -d "$camdir$day" ] || continue
        scan_filter "$LOG.filter.$$"
        rclone copy "$camdir$day" "$REMOTE/$cam/$day" --filter-from "$LOG.filter.$$" \
          --min-age 10s --transfers 3 -v --stats-one-line >"$tmperr" 2>&1; rc=$?
        cat "$tmperr" >>"$LOG" 2>/dev/null              # keep the -v "Copied (new)" lines latency-report needs
        backstop_audit "$tmperr" "fast scan"
        if [ "$rc" -ne 0 ]; then
          reason=$(classify_sync_err "$tmperr")
          log "!! fast lane failed for $cam/$day ($reason)"
          log_event "$cam" sync error "fast lane $day: $reason"
          fast_ok=0; last_error="$reason"; last_error_ts=$now
        fi
      done
    done
    last_fast=$now
  fi
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
    scan_filter "$LOG.filter.$$"
    rclone copy "$CAMERAS_DIR" "$REMOTE" --filter-from "$LOG.filter.$$" --min-age 10s \
         --transfers 3 --fast-list -v --stats-one-line >"$tmperr" 2>&1; rc=$?
    cat "$tmperr" >>"$LOG" 2>/dev/null
    backstop_audit "$tmperr" "self-heal"
    if [ "$rc" -eq 0 ]; then
      uploaded_ok=1; last_heal_ok=$now
    else
      reason=$(classify_sync_err "$tmperr")
      log "!! self-heal copy failed ($reason); NOT purging local"
      log_event "" sync error "self-heal copy: $reason"
      last_error="$reason"; last_error_ts=$now
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
      # The optional _<camera> tag (clips since 2026-09-26) keeps a favourite from also sparing another
      # camera's same-second clip; a legacy untagged name still matches its own mp4 + jpg.
      fav_excl=""
      favs=$(rclone cat "${ROOT_REMOTE}favorites.json" 2>/dev/null | fav_basenames)
      if [ -n "$favs" ]; then
        fav_excl="$HOME/.fav_excludes"
        printf '%s.*\n' $favs > "$fav_excl" 2>/dev/null    # one "mt_YYYYMMDD_HHMMSS[_cam].*" rule per favourite (mp4 + jpg)
        log "cloud retention: sparing $(printf '%s\n' "$favs" | grep -c .) favourite(s) from the >${CLOUD_KEEP_DAYS}d purge"
      fi
      # --drive-use-trash=false: delete for real. Without it the sweep only moves clips to the Drive
      # trash, whose bytes STILL count against the quota — see the TRASH note in the header.
      if [ -n "$fav_excl" ]; then
        rclone delete "$REMOTE" --exclude-from "$fav_excl" --include "*.mp4" --include "*.jpg" --min-age "${CLOUD_KEEP_DAYS}d" --fast-list --drive-use-trash=false >>"$LOG" 2>&1 || true
      else
        rclone delete "$REMOTE" --include "*.mp4" --include "*.jpg" --min-age "${CLOUD_KEEP_DAYS}d" --fast-list --drive-use-trash=false >>"$LOG" 2>&1 || true
      fi
      log "cloud retention sweep (removed Drive files > ${CLOUD_KEEP_DAYS}d)"
      last_retention=$now; last_retention_ok=$now
    fi
  fi

  # Clips that finished while a heal/retention pass ran should not wait a whole extra cycle.
  drain_queue || fast_ok=0

  # 4) QUOTA PROBE (every QUOTA_EVERY) — independent of the heal/retention timers so the headroom
  #    reading stays fresh even during a long offline spell (it just fails and keeps the old value).
  #    Runs AFTER retention in the same pass, so a purge that frees space re-measures immediately.
  if [ "$((now - last_quota))" -ge "$QUOTA_EVERY" ]; then
    probe_quota || true
    last_quota=$now
  fi

  write_sync_status                                   # once per cycle: 'updated' + per-lane ok epochs + last error + quota
  trim_log
  trim_events
  rm -f "$tmperr" "$LOG.filter.$$" 2>/dev/null        # this cycle's captured rclone output + scan filter
  sleep "$INTERVAL"
done
