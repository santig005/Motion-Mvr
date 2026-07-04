#!/data/data/com.termux/files/usr/bin/bash
# watchdog.sh — Self-healing watchdog for the NVR. Keeps alive:
#   - the wake-lock (so the CPU doesn't sleep),
#   - sshd (so you can always administer the phone),
#   - the tmux sessions cam1 (record+detect) and cloud (upload to Drive).
# If anything dies (ffmpeg crash, a stuck camera that takes the script down, etc.) it brings it
# back on the next cycle. It does NOT depend on cron or termux-api: just a simple loop.
# The boot script launches it; running in its own tmux session, it survives SSH disconnects.
set -u
INTERVAL="${WATCH_INTERVAL:-120}"                 # how often (seconds) it checks
LOG="${WATCH_LOG:-$HOME/logs/watchdog.log}"
mkdir -p "$(dirname "$LOG")"
log(){ echo "$(date '+%F %T') $*" >> "$LOG"; }

ensure_session(){ # $1=session name  $2=command to run
  if ! tmux has-session -t "$1" 2>/dev/null; then
    tmux new-session -d -s "$1" "$2" && log "▶ revived session '$1'"
  fi
}

log "=== watchdog starts (checks every ${INTERVAL}s) ==="
while true; do
  termux-wake-lock 2>/dev/null || true                      # idempotent: keeps the lock
  pgrep -x sshd >/dev/null 2>&1 || { sshd 2>/dev/null && log "▶ revived sshd"; }
  ensure_session cam1  "cd ~ && CAM_ENV=\$HOME/cam1.env exec ./record-preroll.sh"
  ensure_session cloud "cd ~ && exec ./cloud-sync.sh"
  sleep "$INTERVAL"
done
