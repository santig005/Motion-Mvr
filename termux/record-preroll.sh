#!/data/data/com.termux/files/usr/bin/bash
# record-preroll.sh — Continuous recording with PRE-ROLL (mini-NVR) for one camera.
#
# Three pieces, one session:
#   1) SEGMENTER: a permanent ffmpeg records the 2K stream (ch0) into fixed segments in a ring
#      buffer (RING_DIR), outside the folder that gets uploaded. With a long GOP each segment lasts
#      about as long as the GOP (~12s).
#   2) DETECTOR: detects motion on the cheap 360p sub-stream (ch1) and writes the motion WINDOWS
#      (start end, epoch) to a file. Same debounce/threshold as a plain motion recorder.
#   3) KEEPER: moves to OUT_DIR the segments that overlap a motion window (with their pre-roll
#      already inside the segment) + metrics + notifies the gallery; deletes idle ring segments.
#
# Why pre-roll over record-on-trigger: latency ~0 (always recording) and it captures the moment
# BEFORE the trigger; it never misses short events (detection only decides what to keep).
# Uses 2 RTSP connections (record on ch0, detect on ch1).
#
# Config (from ~/cam.env or $CAM_ENV). Reuses the detector vars and adds:
#   RING_DIR        ring buffer of segments (default: /sdcard/Movies/.ring/cam1; MUST be OUTSIDE Cameras)
#   SEG_TIME        target segment duration in s (real ~GOP; default: 4)
#   PREROLL         seconds before motion to keep (default: 3) [slack for neighbouring segments]
#   POSTROLL        seconds after the DETECTOR's last motion (default: 5). Two jobs: (a) gap to MERGE
#                   motions into one event/clip, (b) segment slack. NOT the final clip tail: the real
#                   tail is set by TAIL_PAD (content-based trimming).
#   TAIL_PAD        seconds of tail after the last REAL motion measured on the 2K (default: 2.5). The
#                   keeper profiles each clip and trims the dead tail to last_motion + TAIL_PAD
#                   (immune to the 360<->2K skew). Raising POSTROLL to merge long pauses does NOT
#                   lengthen the clip tail.
#   RING_KEEP_MIN   minutes an idle segment lives in the ring before being deleted (default: 5)
#   BATTERY_HIST            local file with recent (epoch,pct) discharge samples (default:
#                           ~/.battery_hist_<cam>; NOT uploaded). Reset whenever charging=true.
#   BATTERY_HIST_WINDOW_SECS regression window for the discharge-rate estimate (default: 14400 = 4h)
#   BATTERY_FLOOR_PCT       battery % the ETA extrapolates to (default: 5)
set -u
ENV_FILE="${CAM_ENV:-$HOME/cam.env}"
if [ -f "$ENV_FILE" ]; then . "$ENV_FILE"; fi

: "${RTSP_MAIN:?Missing RTSP_MAIN}"               # 2K stream that gets recorded (segmenter)
RTSP_DETECT="${RTSP_DETECT:-$RTSP_MAIN}"          # detection stream (360p)
RTSP_TIMEOUT="${RTSP_TIMEOUT:-10000000}"
OUT_DIR="${OUT_DIR:-$HOME/recordings}"            # final clips (these get uploaded)
RING_DIR="${RING_DIR:-/sdcard/Movies/.ring/cam1}"
SEG_TIME="${SEG_TIME:-4}"
PREROLL="${PREROLL:-3}"
POSTROLL="${POSTROLL:-5}"
TAIL_PAD="${TAIL_PAD:-2.5}"
RING_KEEP_MIN="${RING_KEEP_MIN:-5}"
FAIL_THRESHOLD="${FAIL_THRESHOLD:-5}"           # consecutive connection failures before flagging the camera down
HEALTHY_SECS="${HEALTHY_SECS:-8}"               # if a connection lasted >= this, count it as healthy (reset failures)
RETRY_MAX="${RETRY_MAX:-60}"                     # cap of the retry backoff (s); avoids hammering the camera
DET_FPS="${DET_FPS:-6}"
DET_CROP="${DET_CROP:-210:360:226:0}"
DIFF_TH="${DIFF_TH:-20}"
YAVG_TH="${YAVG_TH:-0.4}"
DEBOUNCE="${DEBOUNCE:-2}"
MIN_CLIP="${MIN_CLIP:-1.5}"
METRIC_CROP="${METRIC_CROP:-760:1296:818:0}"
LOG="${LOG:-$HOME/logs/cam1.motion.log}"
METRICS="${METRICS:-$OUT_DIR/metrics.csv}"
MWIN="$RING_DIR/.motion_windows"                  # motion windows (epoch): "start end"
HEALTH_FILE="${HEALTH_FILE:-$OUT_DIR/status.json}"          # camera health (uploaded to Drive; read by the app)
CAM_LABEL="${CAM_LABEL:-$(basename "$OUT_DIR")}"             # e.g. "cam1" (OUT_DIR = camera root)
STALE_SECS="${STALE_SECS:-75}"                   # no new segment for > this => recording down (segments ~12s)
HEARTBEAT_SECS="${HEARTBEAT_SECS:-1200}"         # periodic status.json refresh (heartbeat + battery), ~20min
BATTERY_HIST="${BATTERY_HIST:-$HOME/.battery_hist_$CAM_LABEL}"   # local-only (NOT uploaded): recent (epoch,pct) while discharging
BATTERY_HIST_WINDOW_SECS="${BATTERY_HIST_WINDOW_SECS:-14400}"    # regression window for the discharge rate (~4h)
BATTERY_FLOOR_PCT="${BATTERY_FLOOR_PCT:-5}"                      # % the ETA extrapolates to (phone effectively dead)

mkdir -p "$OUT_DIR" "$RING_DIR" "$(dirname "$LOG")"
: > "$MWIN" 2>/dev/null || true
touch "$RING_DIR/.nomedia" 2>/dev/null            # keep the gallery from indexing the ring
log(){ echo "$(date '+%F %T') $*" | tee -a "$LOG"; }

# NVR health -> status.json (cloud-sync uploads it; the app reads it). The recording signal is
# SEGMENT FRESHNESS (are new seg_*.mp4 files appearing?), which is what actually matters. The keeper
# writes it: on transitions (ok<->down) and as a heartbeat every HEARTBEAT_SECS (for battery and so
# the app can detect "NVR not reporting" if the updated field goes stale).

# Reads the battery via termux-api (if installed + Termux:API app). Prints "PCT true|false" or fails.
read_battery(){
  command -v termux-battery-status >/dev/null 2>&1 || return 1
  local j pct st chg=false
  j=$(timeout 8 termux-battery-status 2>/dev/null) || return 1
  pct=$(printf '%s' "$j" | grep -o '"percentage"[^,}]*' | grep -o '[0-9]\+' | head -1)
  st=$(printf '%s' "$j" | grep -o '"status"[^,}]*' | sed 's/.*: *"//; s/".*//')
  [ -z "$pct" ] && return 1
  case "$st" in CHARGING|FULL) chg=true;; esac
  echo "$pct $chg"
}

# Battery autonomy estimate. Piggybacks on the existing heartbeat (no new wakeups). While
# discharging, appends (epoch,pct) to a small local history file and fits a simple linear
# regression over the recent window to get a %/h rate, extrapolated to BATTERY_FLOOR_PCT for an
# ETA in minutes. Charging resets the history (the slope changes sign, old points are meaningless).
# Not uploaded: it's phone-local state, not something the app needs to see directly.
update_battery_history(){ # $1=pct  $2=charging(true/false)
  local pct="$1" chg="$2" now
  now=$(date +%s)
  if [ "$chg" = "true" ]; then
    : > "$BATTERY_HIST" 2>/dev/null
    return
  fi
  echo "$now $pct" >> "$BATTERY_HIST" 2>/dev/null
  awk -v cutoff="$((now - BATTERY_HIST_WINDOW_SECS))" '$1>=cutoff' "$BATTERY_HIST" > "$BATTERY_HIST.tmp" 2>/dev/null \
    && mv -f "$BATTERY_HIST.tmp" "$BATTERY_HIST" 2>/dev/null
}

# Prints "pct_per_h eta_minutes" or fails (nothing printed) if there isn't enough signal yet.
compute_battery_eta(){ # $1=current pct
  [ -s "$BATTERY_HIST" ] || return 1
  awk -v cur="$1" -v floor="$BATTERY_FLOOR_PCT" '
    { n++; x[n]=$1; y[n]=$2 }
    END {
      if (n < 2) exit 1
      # Running-minimum filter: drop upward blips (battery-reporting noise) while discharging,
      # so the regression sees a monotonic-ish trend instead of jitter.
      fm = y[1]; fn = 0
      for (i=1; i<=n; i++) if (y[i] <= fm) { fm=y[i]; fn++; fx[fn]=x[i]; fy[fn]=y[i] }
      if (fn < 2) exit 1
      sx=0; sy=0
      for (i=1; i<=fn; i++) { sx+=fx[i]; sy+=fy[i] }
      mx=sx/fn; my=sy/fn
      num=0; den=0
      for (i=1; i<=fn; i++) { dx=fx[i]-mx; num+=dx*(fy[i]-my); den+=dx*dx }
      if (den <= 0) exit 1
      rate_h = -(num/den) * 3600          # %/h; positive while discharging
      if (rate_h <= 0.01) exit 1          # flat/insufficient signal -> no estimate
      eta = (cur - floor) / rate_h * 60
      if (eta < 0) eta = 0
      printf "%.2f %d", rate_h, eta
    }'
}

write_status(){ # $1=recording_ok(1/0)  $2=heartbeat(1/0, default 0)
  local rec now bat pct chg eta extra="" hb="${2:-0}"
  [ "$1" = 1 ] && rec=true || rec=false
  now=$(date +%s)
  if bat=$(read_battery); then
    pct=${bat% *}; chg=${bat#* }
    extra=",\"battery\":${pct},\"charging\":${chg}"
    # Only sample into BATTERY_HIST on the heartbeat (~every HEARTBEAT_SECS), not on every
    # ok<->down transition: those can fire seconds apart during a flapping camera reconnect, and
    # a single stray CHARGING reading in the middle of a flap would otherwise wipe hours of
    # discharge history (write_status runs on transitions too, for the recording_ok signal).
    [ "$hb" = 1 ] && update_battery_history "$pct" "$chg"
    if eta=$(compute_battery_eta "$pct"); then
      extra="${extra},\"discharge_pct_per_h\":${eta% *},\"eta_minutes\":${eta#* }"
    fi
  fi
  printf '{"camera":"%s","ok":%s,"recording_ok":%s,"updated":%d%s}\n' \
    "$CAM_LABEL" "$rec" "$rec" "$now" "$extra" \
    > "$HEALTH_FILE.tmp" 2>/dev/null && mv -f "$HEALTH_FILE.tmp" "$HEALTH_FILE" 2>/dev/null
}

seg_epoch(){ # seg_YYYYMMDD_HHMMSS -> epoch
  local ts=${1#seg_}
  date -d "${ts:0:4}-${ts:4:2}-${ts:6:2} ${ts:9:2}:${ts:11:2}:${ts:13:2}" +%s 2>/dev/null
}

# Writes the metrics row with values ALREADY computed by the profiler (no re-decoding).
write_metrics_row(){ # $1=final file  $2=yavg_max  $3=yavg_mean  $4=motion_frames
  local f="$1" mx="$2" mean="$3" n="$4" dur sz base
  dur=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$f" 2>/dev/null)
  sz=$(( $(stat -c %s "$f" 2>/dev/null) / 1024 ))
  [ -f "$METRICS" ] || echo "clip,datetime,dur_s,size_kb,yavg_max,yavg_mean,motion_frames" > "$METRICS"
  base=$(basename "$f" .mp4)
  echo "$base,${base#mt_},${dur:-0},$sz,$mx,$mean,$n" >> "$METRICS"
  am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$f" >/dev/null 2>&1
  log "📊 $(basename "$f") dur=${dur}s yavg_max=$mx motion_frames=$n -> gallery"
}

# Profiles the REAL motion over the window [offset, offset+dur] of the concat (the 2K itself).
# A single decode that serves to (a) find the last motion and (b) compute the metrics.
# Prints "m0 m1 cut mx mean n" (times relative to offset, in s) or "NOMOTION".
profile_motion(){ # $1=concat_list  $2=offset  $3=dur
  local list="$1" offset="$2" dur="$3"
  ffmpeg -nostdin -loglevel info -f concat -safe 0 -i "$list" -ss "$offset" -t "$dur" -an \
      -vf "fps=$DET_FPS,${METRIC_CROP:+crop=$METRIC_CROP,}tblend=all_mode=difference,format=gray,lut=y=if(gt(val\,$DIFF_TH)\,255\,0),signalstats,metadata=print" \
      -f null - 2>&1 | awk -v th="$YAVG_TH" -v tp="$TAIL_PAD" '
        /pts_time:/         { ln=$0; sub(/.*pts_time:/,"",ln); sub(/[^0-9.].*/,"",ln); t=ln+0 }
        /signalstats.YAVG=/ { v=$0; sub(/.*YAVG=/,"",v); sub(/ .*/,"",v); v=v+0;
                              nf++; T[nf]=t; Y[nf]=v; if(v>th){ if(g==0){m0=t; g=1} m1=t } }
        END{ if(g==0){ print "NOMOTION"; exit }
             cut=m1+tp; mx=0; s=0; c=0; cnt=0;
             for(i=1;i<=nf;i++){ if(T[i]<=cut){ c++; s+=Y[i]; if(Y[i]>mx)mx=Y[i]; if(Y[i]>th)cnt++ } }
             printf "%.3f %.3f %.3f %.3f %.3f %d", m0, m1, cut, mx, (c?s/c:0), cnt }'
}

# 1) SEGMENTER (supervised) -------------------------------------------------------
segmenter_loop(){
  local sfails=0 t0 ran delay
  while true; do
    log "▶ segmenter connecting ($(echo "$RTSP_MAIN"|sed 's#//[^@]*@#//...@#'))"
    t0=$(date +%s)
    ffmpeg -nostdin -loglevel error -rtsp_transport tcp -timeout "$RTSP_TIMEOUT" -i "$RTSP_MAIN" \
      -c:v copy -c:a aac -f segment -segment_time "$SEG_TIME" -reset_timestamps 1 -strftime 1 \
      "$RING_DIR/seg_%Y%m%d_%H%M%S.mp4" >>"$LOG" 2>&1
    ran=$(( $(date +%s) - t0 ))
    if [ "$ran" -ge "$HEALTHY_SECS" ]; then sfails=0; else sfails=$((sfails+1)); fi
    # Staggered backoff so we don't hammer the camera while it's down (gives it room to recover).
    if [ "$sfails" -le 1 ]; then delay=5; else delay=$((sfails*8)); [ "$delay" -gt "$RETRY_MAX" ] && delay="$RETRY_MAX"; fi
    log "!! segmenter dropped (ran ${ran}s, failure $sfails); retry in ${delay}s"; sleep "$delay"
  done
}

# 3) KEEPER/PRUNER ----------------------------------------------------------------
build_clip(){ # $1=clip_start_epoch  $2=clip_end_epoch  $3=newest_seg(open, to skip)
  local clip_start="$1" clip_end="$2" newest="$3" list ss dseg se first_start offset dur ts dst segcount=0
  list="$RING_DIR/.cat_$$_${clip_start}.txt"; : > "$list"; first_start=""
  for seg in $(ls -1 "$RING_DIR"/seg_*.mp4 2>/dev/null); do
    [ "$seg" = "$newest" ] && continue
    ss=$(seg_epoch "$(basename "$seg" .mp4)"); [ -z "$ss" ] && continue
    dseg=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$seg" 2>/dev/null)
    se=$(awk "BEGIN{printf \"%d\", $ss + (${dseg:-0}+0)}")
    if [ "$se" -ge "$clip_start" ] && [ "$ss" -le "$clip_end" ]; then
      printf "file '%s'\n" "$seg" >> "$list"; [ -z "$first_start" ] && first_start=$ss; segcount=$((segcount+1))
    fi
  done
  if [ "$segcount" -eq 0 ]; then rm -f "$list"; return 1; fi
  offset=$((clip_start - first_start)); [ "$offset" -lt 0 ] && offset=0
  dur=$((clip_end - clip_start)); [ "$dur" -lt 1 ] && dur=1
  ts=$(date -d "@$clip_start" "+%Y%m%d_%H%M%S" 2>/dev/null)
  # Organized as Camera/Year/Month/Day: the clip (and its thumbnail) go to OUT_DIR/YYYY/MM/DD/.
  local datedir; datedir="$OUT_DIR/$(date -d "@$clip_start" "+%Y/%m/%d" 2>/dev/null)"
  mkdir -p "$datedir"
  dst="$datedir/mt_${ts}.mp4"
  # Profile the 2K (one decode) to find the last REAL motion and the tail to trim.
  local prof m0 m1 cut mx mean n final_dur
  prof=$(profile_motion "$list" "$offset" "$dur")
  if [ "$prof" = "NOMOTION" ] || [ -z "$prof" ]; then
    final_dur="$dur"; mx=0; mean=0; n=0; m1="NA"
    log "… no measurable motion on 2K for mt_${ts}; keeping full window (${dur}s)"
  else
    read -r m0 m1 cut mx mean n <<<"$prof"
    final_dur=$(awk "BEGIN{d=$cut; if(d>$dur)d=$dur; if(d<1)d=1; printf \"%.3f\", d}")
  fi
  # EXACT trim with re-encode (copy segments can't cut finer than the GOP).
  # Window = [offset, offset+final_dur]: pre-roll intact, dead tail trimmed to the real content.
  # ATOMIC write: ffmpeg writes to a .part and only renames to mt_*.mp4 when finished. That way
  # rclone (cloud-sync) NEVER sees a half-written file -> no partial/corrupt uploads.
  local part="${dst}.part"
  # -f mp4 REQUIRED: the .part destination doesn't let ffmpeg infer the format from the extension.
  if ffmpeg -nostdin -loglevel error -f concat -safe 0 -i "$list" -ss "$offset" -t "$final_dur" \
       -c:v libx264 -preset veryfast -crf 23 -threads 2 -pix_fmt yuv420p -c:a aac -movflags +faststart -f mp4 "$part" 2>>"$LOG"; then
    mv -f "$part" "$dst"
    make_thumb "$dst" "$final_dur"
    rm -f "$list"; write_metrics_row "$dst" "$mx" "$mean" "$n"
    log "✂️ mt_${ts} (offset=${offset}s window=${dur}s -> trimmed=${final_dur}s, motion_end=${m1}s, $segcount seg)"
  else
    rm -f "$list" "$part"; log "!! trim failed for mt_${ts}"
  fi
}

# JPG thumbnail for the app: a representative frame (~4s, or midpoint for short clips).
# Atomic write (.part + mv) just like the mp4, so rclone never uploads a half-written jpg.
make_thumb(){ # $1=final mp4  $2=duration s
  local mp4="$1" d="$2" jpg="${1%.mp4}.jpg" ss
  ss=$(awk "BEGIN{d=$d+0; printf \"%.1f\", (d>5?4:d/2)}")
  # -f mjpeg: the .part destination doesn't let ffmpeg infer the format from the extension.
  if ffmpeg -nostdin -loglevel error -ss "$ss" -i "$mp4" -frames:v 1 -q:v 4 -f mjpeg "${jpg}.part" 2>>"$LOG"; then
    mv -f "${jpg}.part" "$jpg"
  else
    rm -f "${jpg}.part"; log "!! thumbnail failed for $(basename "$jpg")"
  fi
}

keeper_loop(){
  local now newest newest_start es el clip_start clip_end ss
  local rec_state="" last_hb=0 started rec_ok mt age
  started=$(date +%s)
  while true; do
    sleep 5
    now=$(date +%s)
    newest=$(ls -1t "$RING_DIR"/seg_*.mp4 2>/dev/null | head -n1)
    # --- RECORDING health by segment freshness: was the newest one written recently? ---
    if [ -n "$newest" ]; then
      mt=$(stat -c %Y "$newest" 2>/dev/null || echo 0); age=$((now - mt))
      [ "$age" -lt "$STALE_SECS" ] && rec_ok=1 || rec_ok=0
    else
      # no segments: grace at startup; past the grace window = down
      [ "$((now - started))" -lt "$STALE_SECS" ] && rec_ok=1 || rec_ok=0
    fi
    if [ "$rec_ok" = 1 ] && [ "$rec_state" != "ok" ]; then
      write_status 1; rec_state=ok; last_hb=$now; [ -n "$newest" ] && log "✅ recording (fresh segments)"
    elif [ "$rec_ok" = 0 ] && [ "$rec_state" != "down" ]; then
      write_status 0; rec_state=down; last_hb=$now
      log "⚠️ RECORDING DOWN (no new segment for >${STALE_SECS}s) -> $HEALTH_FILE"
    elif [ "$((now - last_hb))" -ge "$HEARTBEAT_SECS" ]; then
      write_status "$rec_ok" 1; last_hb=$now                # heartbeat: refresh updated + battery + history sample
    fi
    [ -z "$newest" ] && continue
    newest_start=$(seg_epoch "$(basename "$newest" .mp4)")
    # Process READY motion windows (their clip_end is already in complete segments)
    if [ -s "$MWIN" ]; then
      : > "$MWIN.keep"
      while read -r es el; do
        [ -z "${es:-}" ] && continue
        clip_start=$((es - PREROLL)); clip_end=$((el + POSTROLL))
        if [ -z "$newest_start" ] || [ "$newest_start" -le "$clip_end" ]; then
          echo "$es $el" >> "$MWIN.keep"          # still recording; retry later
        elif [ "$((now - el))" -le "$((RING_KEEP_MIN*60))" ]; then
          build_clip "$clip_start" "$clip_end" "$newest" &   # ready: trim (in background)
        fi
        # (if too old -> segments already pruned -> dropped by not re-writing it)
      done < "$MWIN"
      mv "$MWIN.keep" "$MWIN" 2>/dev/null
    fi
    # Prune the ring by age (from the name, no heavy subprocesses)
    for seg in "$RING_DIR"/seg_*.mp4; do
      [ -e "$seg" ] || continue
      ss=$(seg_epoch "$(basename "$seg" .mp4)"); [ -z "$ss" ] && continue
      [ "$((now - ss))" -gt "$((RING_KEEP_MIN*60))" ] && rm -f "$seg"
    done
  done
}

cleanup(){ log "=== exiting ==="; kill "$SEG_PID" "$KEEP_PID" 2>/dev/null; pkill -P $$ 2>/dev/null; exit 0; }
trap cleanup INT TERM

segmenter_loop & SEG_PID=$!
keeper_loop   & KEEP_PID=$!

log "=== record-preroll starts | detect=${RTSP_DETECT##*/} record=${RTSP_MAIN##*/} | fps=$DET_FPS yavg=$YAVG_TH deb=$DEBOUNCE preroll=${PREROLL}s post(gap)=${POSTROLL}s tail_pad=${TAIL_PAD}s seg~GOP ring=${RING_KEEP_MIN}min ==="

# 2) DETECTOR (foreground) — writes motion windows to $MWIN --------------------------
over=0; connected=0; dfails=0
while true; do
  det_t0=$(date +%s)
  ffmpeg -nostdin -loglevel info -rtsp_transport tcp -timeout "$RTSP_TIMEOUT" -i "$RTSP_DETECT" -an \
    -vf "fps=$DET_FPS,${DET_CROP:+crop=$DET_CROP,}tblend=all_mode=difference,format=gray,lut=y=if(gt(val\,$DIFF_TH)\,255\,0),signalstats,metadata=print" \
    -f null - 2>&1 >/dev/null |
  grep --line-buffered -F 'signalstats.YAVG=' |
  ( in_evt=0; evt_start=0; evt_last=0
    while true; do
      if IFS= read -r -t 2 line; then
        [ "$connected" -eq 0 ] && { log "▶ detector connected (${RTSP_DETECT##*/})"; connected=1; }
        val=${line##*YAVG=}; val=${val%% *}
        if awk "BEGIN{exit !(${val}+0 > $YAVG_TH)}" 2>/dev/null; then over=$((over+1)); else over=0; fi
        if [ "$over" -ge "$DEBOUNCE" ]; then
          now=$(date +%s)
          [ "$in_evt" -eq 0 ] && { in_evt=1; evt_start=$now; log "►► motion (event open)"; }
          evt_last=$now
        fi
      else
        [ "$?" -le 128 ] && { log "!! detector pipe closed"; break; }
      fi
      if [ "$in_evt" -eq 1 ]; then
        now=$(date +%s)
        if [ "$((now - evt_last))" -ge "$POSTROLL" ]; then
          echo "$evt_start $evt_last" >> "$MWIN"
          log "■■ event closed ($evt_start..$evt_last); the keeper will preserve its segments"
          in_evt=0
        fi
      fi
    done )
  connected=0
  det_ran=$(( $(date +%s) - det_t0 ))
  if [ "$det_ran" -ge "$HEALTHY_SECS" ]; then
    dfails=0; delay=5
  else
    dfails=$((dfails+1))
    # Health/alerting is decided by the keeper via segment freshness; here we only back off the detector.
    [ "$dfails" -eq "$FAIL_THRESHOLD" ] && log "⚠️ detector (ch1) can't connect after $dfails tries"
    delay=$((dfails*8)); [ "$delay" -gt "$RETRY_MAX" ] && delay="$RETRY_MAX"
  fi
  log "!! detector ended (ran ${det_ran}s, failure $dfails); retry in ${delay}s"; sleep "$delay"
done
