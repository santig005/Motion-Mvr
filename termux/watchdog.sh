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
RING_DIR="${RING_DIR:-/sdcard/Movies/.ring/cam1}"  # segmenter ring (must match cam1.env)
STALE_KICK="${STALE_KICK:-150}"                   # seconds without a new segment (cam1 alive) before force-restarting ffmpeg
mkdir -p "$(dirname "$LOG")"
log(){ echo "$(date '+%F %T') $*" >> "$LOG"; }

ensure_session(){ # $1=session name  $2=command to run
  if ! tmux has-session -t "$1" 2>/dev/null; then
    tmux new-session -d -s "$1" "$2" && log "▶ revived session '$1'"
  fi
}

# If cam1 is alive but the segmenter stopped writing segments, kill its ffmpeg (it reconnects on its
# own). Only fires on real staleness: with a healthy camera the newest segment is <20s old and never
# crosses STALE_KICK. Killing ffmpeg during a genuine outage is harmless (it was going to retry anyway).
kick_stuck_segmenter(){
  tmux has-session -t cam1 2>/dev/null || return 0
  local newest age
  newest=$(ls -t "$RING_DIR"/seg_*.mp4 2>/dev/null | head -1)
  [ -z "$newest" ] && return 0                     # no segments yet (startup/long outage): the loop already retries
  age=$(( $(date +%s) - $(stat -c %Y "$newest" 2>/dev/null || echo 0) ))
  if [ "$age" -gt "$STALE_KICK" ]; then
    if pkill -f "$RING_DIR/seg_" 2>/dev/null; then
      log "🔧 segmenter stuck (${age}s with no new segment): ch0 ffmpeg restarted"
    fi
  fi
}

log "=== watchdog starts (checks every ${INTERVAL}s; segmenter kick at ${STALE_KICK}s) ==="
while true; do
  termux-wake-lock 2>/dev/null || true                      # idempotent: keeps the lock
  pgrep -x sshd >/dev/null 2>&1 || { sshd 2>/dev/null && log "▶ revived sshd"; }
  ensure_session cam1  "cd ~ && CAM_ENV=\$HOME/cam1.env exec ./record-preroll.sh"
  ensure_session cloud "cd ~ && exec ./cloud-sync.sh"
  kick_stuck_segmenter
  sleep "$INTERVAL"
done
