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
set -u
INTERVAL="${WATCH_INTERVAL:-120}"                 # how often (seconds) it checks
LOG="${WATCH_LOG:-$HOME/logs/watchdog.log}"
CAMS="${CAMS:-cam1}"                              # space-separated camera session names; each has ~/<cam>.env. Do NOT auto-discover cam*.env (cam360.env is a profile, not a camera)
RING_BASE="${RING_BASE:-/sdcard/Movies/.ring}"    # per-camera segmenter ring at $RING_BASE/<cam> (must match each cam's env; cam1 => /sdcard/Movies/.ring/cam1)
STALE_KICK="${STALE_KICK:-150}"                   # seconds without a new segment (session alive) before force-restarting ffmpeg
LOG_MAX_KB="${LOG_MAX_KB:-1024}"                  # cap on watchdog.log before it's trimmed to its newest half
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

log "=== watchdog starts (checks every ${INTERVAL}s; segmenter kick at ${STALE_KICK}s; cams: ${CAMS}) ==="
while true; do
  termux-wake-lock 2>/dev/null || true                      # idempotent: keeps the lock
  pgrep -x sshd >/dev/null 2>&1 || { sshd 2>/dev/null && log "▶ revived sshd"; }
  for cam in $CAMS; do
    ensure_session "$cam" "cd ~ && CAM_ENV=\$HOME/$cam.env exec ./record-preroll.sh"
    kick_stuck_segmenter "$cam" "$RING_BASE/$cam"
  done
  ensure_session cloud "cd ~ && exec ./cloud-sync.sh"
  trim_log
  sleep "$INTERVAL"
done
