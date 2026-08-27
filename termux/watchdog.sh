#!/data/data/com.termux/files/usr/bin/bash
# watchdog.sh — Self-healing watchdog for the NVR. Keeps alive:
#   - the wake-lock (so the CPU doesn't sleep),
#   - sshd (so you can always administer the phone),
#   - the tmux sessions cam1 (record+detect) and cloud (upload to Drive).
# If anything dies (ffmpeg crash, a stuck camera that takes the script down, etc.) it brings it
# back on the next cycle. It does NOT depend on cron or termux-api: just a simple loop.
# The boot script launches it; running in its own tmux session, it survives SSH disconnects.
#
# SELF-HEAL A STUCK SEGMENTER (ffmpeg alive but producing nothing): besides reviving dead sessions,
# the watchdog watches SEGMENT FRESHNESS. A ch0 ffmpeg can stay ALIVE yet emit no video (the camera
# keeps the TCP socket open but stops sending frames after a blip → ffmpeg's -timeout never fires and
# it hangs indefinitely). "Process alive" != "producing". If cam1 is alive but the ring has had no
# new segment for > STALE_KICK, kill the segmenter ffmpeg so its loop reconnects fresh.
#
# RECOVER A BLIND DETECTOR (segments landing, but nothing detecting them): the checks above are all
# about the SEGMENTER. Nothing here ever looked at the detector, and "ring is fresh" is not the same
# as "clips are being produced" — clips are motion-triggered, so with the detector dead we record
# 24/7 and build NOTHING. On 2026-08-16 that ran for 13h40m: the NVR classified it correctly, wrote
# detector_ok:false, uploaded it, and the phone alerted the user — while this watchdog, on the same
# device, concluded "all good" and never attempted a single recovery. See kick_blind_detector.
set -u
INTERVAL="${WATCH_INTERVAL:-120}"                 # how often (seconds) it checks
LOG="${WATCH_LOG:-$HOME/logs/watchdog.log}"
CAMS="${CAMS:-cam1}"                              # space-separated camera session names; each has ~/<cam>.env. Do NOT auto-discover cam*.env (cam360.env is a profile, not a camera)
RING_BASE="${RING_BASE:-/sdcard/Movies/.ring}"    # per-camera segmenter ring at $RING_BASE/<cam> (must match each cam's env; cam1 => /sdcard/Movies/.ring/cam1)
STALE_KICK="${STALE_KICK:-150}"                   # seconds without a new segment (session alive) before force-restarting ffmpeg
LOG_MAX_KB="${LOG_MAX_KB:-1024}"                  # cap on watchdog.log before it's trimmed to its newest half
DET_BLIND_KICK="${DET_BLIND_KICK:-300}"           # seconds with .det_state = DOWN before the first session restart
DET_KICK_MAX="${DET_KICK_MAX:-3}"                 # give up after this many restarts in one episode (see backoff note below)
DET_KICK_BACKOFF="${DET_KICK_BACKOFF:-3}"         # each further attempt waits DET_BLIND_KICK * this^n (300s, 900s, 2700s)
DET_RECOVER_SECS="${DET_RECOVER_SECS:-600}"       # detector must stay healthy this long before an episode counts as OVER (see kick_blind_detector)
declare -A DET_KICKS=() DET_LAST=() DET_WELL=()   # per-camera episode state: attempts made, epoch of the last one, epoch it started looking healthy
mkdir -p "$(dirname "$LOG")"
log(){ echo "$(date '+%F %T') $*" >> "$LOG"; }

# Nothing else rotates this log; cap it so a long uptime can't grow it unbounded (trim to newest half).
trim_log(){
  local sz
  sz=$(stat -c %s "$LOG" 2>/dev/null) || return 0
  [ "$sz" -gt $((LOG_MAX_KB * 1024)) ] || return 0
  tail -c $((LOG_MAX_KB * 1024 / 2)) "$LOG" > "$LOG.tmp" 2>/dev/null && mv -f "$LOG.tmp" "$LOG" 2>/dev/null
}

ensure_session(){ # $1=session name  $2=command to run
  if ! tmux has-session -t "$1" 2>/dev/null; then
    tmux new-session -d -s "$1" "$2" && log "▶ revived session '$1'"
  fi
}

# The command a camera session runs. Factored out because it is now needed in two places: the
# supervision loop, and kick_blind_detector, which must bring the session straight back up.
cam_cmd(){ printf 'cd ~ && CAM_ENV=$HOME/%s.env exec ./record-preroll.sh' "$1"; }

# If a camera session is alive but its segmenter stopped writing segments, kill that ffmpeg (it
# reconnects on its own). Only fires on real staleness: with a healthy camera the newest segment is
# <20s old and never crosses STALE_KICK. Killing ffmpeg during a genuine outage is harmless (it was
# going to retry anyway). Per camera: matches its own ring so a multi-cam setup kicks the right ffmpeg.
kick_stuck_segmenter(){ # $1=cam  $2=ring_dir
  local cam="$1" ring="$2" newest age
  tmux has-session -t "$cam" 2>/dev/null || return 0
  newest=$(ls -t "$ring"/seg_*.mp4 2>/dev/null | head -1)
  [ -z "$newest" ] && return 0                     # no segments yet (startup/long outage): the loop already retries
  age=$(( $(date +%s) - $(stat -c %Y "$newest" 2>/dev/null || echo 0) ))
  if [ "$age" -gt "$STALE_KICK" ]; then
    if pkill -f "$ring/seg_" 2>/dev/null; then
      log "🔧 segmenter stuck [$cam] (${age}s with no new segment): ch0 ffmpeg restarted"
    fi
  fi
}

# Read one variable out of a camera's env file, without polluting the watchdog's own environment
# (the envs set RTSP_MAIN, OUT_DIR, ... which must not leak between cameras). Subshell + `set +u`
# because those files are written for record-preroll.sh, not for us.
cam_env_var(){ # $1=cam  $2=var name
  ( set +u; . "$HOME/$1.env" 2>/dev/null; eval "printf '%s' \"\${$2:-}\"" )
}

# One short JSON line into the shared event log, same shape record-preroll.sh emits, so a recovery
# attempt is reconstructible afterwards instead of living only in this log. The whole reason the
# 2026-08-16 analysis had to be done by reading cam logs by hand is that nothing recorded WHY.
wlog_event(){ # $1=cam  $2=ev  $3=msg
  local out ev_log
  out=$(cam_env_var "$1" OUT_DIR); [ -n "$out" ] || return 0
  ev_log="$(dirname "$out")/events.jsonl"
  printf '{"ts":%d,"cam":"%s","svc":"watchdog","ev":"%s","msg":"%s"}\n' \
    "$(date +%s)" "$1" "$2" "$3" >> "$ev_log" 2>/dev/null || true
}

# A detector that has been delivering NO frames for DET_BLIND_KICK seconds is not going to fix itself
# by retrying — that is the lesson of 2026-08-12 (978 reconnects) and 2026-08-16 (481). Restart the
# whole camera session, which is exactly the manual fix that ends these episodes: ensure_session
# revives it on the next cycle with fresh ffmpegs and clean state.
#
# The guards below are NOT optional. An eager watchdog is worse than a passive one: get this wrong
# and it restarts the NVR in a loop, so nothing is ever recorded at all.
#   1. PING FIRST — no ping means the camera is off/away, and no local restart can fix that. This is
#      the same split reboot_camera() already makes between "wedged" and "unreachable".
#   2. BACKOFF — the wait after attempt n is DET_BLIND_KICK * DET_KICK_BACKOFF^n, so attempts land
#      about 5, 20 and 65 minutes into an outage; not every INTERVAL for fourteen hours.
#   3. A CEILING — after DET_KICK_MAX we stop and just hold the alarm; power-cycling a dead camera
#      forever only burns the battery that the app is trying to preserve.
#   4. AUTO-RESET — a healthy detector closes the episode, so a later, unrelated failure starts from
#      attempt 1 rather than inheriting an exhausted counter.
# Does the host answer at all? Returns on the FIRST reply, so a healthy camera costs one packet.
host_pings(){ # $1=host  $2=attempts
  local i=0
  while [ "$i" -lt "${2:-3}" ]; do
    ping -c1 -W2 "$1" >/dev/null 2>&1 && return 0
    i=$((i+1))
  done
  return 1
}

kick_blind_detector(){ # $1=cam  $2=ring_dir
  local cam="$1" ring="$2" st since blind n last wait_s host now
  tmux has-session -t "$cam" 2>/dev/null || return 0
  [ -r "$ring/.det_state" ] || return 0
  read -r st since < "$ring/.det_state" 2>/dev/null || return 0
  now=$(date +%s)
  if [ "${st:-}" != "DOWN" ]; then                         # guard 4: is the episode over?
    if [ "${DET_KICKS[$cam]:-0}" -gt 0 ]; then
      # Recovery has to HOLD. Restarting the session makes the fresh process publish a non-DOWN state
      # within seconds, and the old code read that as success: it cleared the attempt counter, so the
      # next kick was "attempt 1" all over again and neither DET_KICK_MAX nor the exponential backoff
      # could ever engage. On 2026-08-26 that produced a kick -> "recovered" -> kick loop every ~368s
      # for ten hours against a camera that needed a power-cycle -- precisely the "restarts the NVR in
      # a loop, so nothing is ever recorded" outcome this function's guards exist to prevent. And the
      # restart cadence itself then kept resetting the segmenter's 360s wedge timer, so the one
      # classifier that could have named the real fault never got to finish counting.
      if [ "${st:-}" = "INIT" ]; then DET_WELL[$cam]=0; return 0; fi   # "no frames yet" is not recovery
      [ "${DET_WELL[$cam]:-0}" -eq 0 ] && DET_WELL[$cam]="$now"
      [ "$(( now - ${DET_WELL[$cam]} ))" -lt "$DET_RECOVER_SECS" ] && return 0
      log "✅ detector [$cam] healthy for $(( now - ${DET_WELL[$cam]} ))s after ${DET_KICKS[$cam]} restart(s)"
      wlog_event "$cam" recovered "detector ok after ${DET_KICKS[$cam]} restart(s)"
      DET_KICKS[$cam]=0; DET_LAST[$cam]=0; DET_WELL[$cam]=0
    fi
    return 0
  fi
  DET_WELL[$cam]=0                                        # still DOWN: any partial recovery streak is void
  blind=$(( now - ${since:-0} ))
  [ "$blind" -ge "$DET_BLIND_KICK" ] || return 0
  n="${DET_KICKS[$cam]:-0}"
  if [ "$n" -ge "$DET_KICK_MAX" ]; then                    # guard 3: ceiling
    [ "${DET_KICKS[$cam]:-0}" = "$DET_KICK_MAX" ] && {
      log "🛑 detector [$cam] still blind after $n restarts (${blind}s) — standing down, alarm holds"
      wlog_event "$cam" giveup "blind ${blind}s after $n restarts"
      DET_KICKS[$cam]=$((DET_KICK_MAX + 1))                # log the give-up once, not every cycle
    }
    return 0
  fi
  last="${DET_LAST[$cam]:-0}"                              # guard 2: backoff
  if [ "$last" -gt 0 ]; then
    wait_s=$(( DET_BLIND_KICK * (DET_KICK_BACKOFF ** n) ))
    [ "$(( now - last ))" -lt "$wait_s" ] && return 0
  fi
  host=$(cam_env_var "$cam" RTSP_MAIN | sed -E 's#^[a-z]+://([^@]*@)?([^:/]+).*#\2#')
  # Several pings, not one: a single dropped ICMP on this -71 dBm link would otherwise stand the
  # watchdog down for a full cycle on a camera that is actually answering (observed 2026-08-26, when
  # the camera replied 12/12 from a shell moments after being declared unreachable).
  if [ -n "$host" ] && ! host_pings "$host" 5; then                  # guard 1: ping
    log "🔌 detector [$cam] blind ${blind}s but camera $host does not ping — external fault, not restarting"
    wlog_event "$cam" skipped "blind ${blind}s, camera unreachable"
    return 0
  fi
  DET_KICKS[$cam]=$((n + 1)); DET_LAST[$cam]="$now"
  log "🔄 detector [$cam] blind ${blind}s — restarting session (attempt ${DET_KICKS[$cam]}/${DET_KICK_MAX})"
  wlog_event "$cam" restart "detector blind ${blind}s, attempt ${DET_KICKS[$cam]}"
  # Kill AND revive in the same pass. Leaving the revival to the next cycle costs a full INTERVAL of
  # not recording — measured live on 2026-08-16: killed at 20:58:26, still gone at 21:00:23. The
  # whole point is to restore detection, so opening a two-minute recording hole to do it is a poor
  # trade, and a watchdog that stops recording is exactly the failure mode to avoid.
  tmux kill-session -t "$cam" 2>/dev/null || true
  sleep 1
  ensure_session "$cam" "$(cam_cmd "$cam")"
}

# Test hook: `WATCHDOG_LIB=1 . watchdog.sh` loads the functions without entering the supervision
# loop, so the recovery ladder above can be unit-tested. Must stay the last line before the loop.
[ "${WATCHDOG_LIB:-0}" = 1 ] && return 0

log "=== watchdog starts (checks every ${INTERVAL}s; segmenter kick at ${STALE_KICK}s; detector kick at ${DET_BLIND_KICK}s; cams: ${CAMS}) ==="
while true; do
  termux-wake-lock 2>/dev/null || true                      # idempotent: keeps the lock
  pgrep -x sshd >/dev/null 2>&1 || { sshd 2>/dev/null && log "▶ revived sshd"; }
  for cam in $CAMS; do
    ensure_session "$cam" "$(cam_cmd "$cam")"
    kick_stuck_segmenter "$cam" "$RING_BASE/$cam"
    kick_blind_detector "$cam" "$RING_BASE/$cam"
  done
  ensure_session cloud "cd ~ && exec ./cloud-sync.sh"
  trim_log
  sleep "$INTERVAL"
done
