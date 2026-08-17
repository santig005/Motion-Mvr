#!/usr/bin/env bash
# Unit tests for the watchdog's blind-detector recovery ladder (termux/watchdog.sh).
#
# This is the one piece of the system that can make things WORSE. Every other component, when it
# gets confused, records too much or reports the wrong number; this one restarts the NVR. A bug here
# means a restart loop in which nothing is ever recorded — strictly worse than the passive watchdog
# it replaces. So the guards (ping, backoff, ceiling, auto-reset) are tested as carefully as the
# happy path, and "does not restart" is asserted at least as often as "does".
#
# Run: bash termux/tests/test-watchdog.sh
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUT="${SUT_OVERRIDE:-$HERE/../watchdog.sh}"
PASS=0; FAIL=0

ok(){ PASS=$((PASS+1)); printf '  \033[32m✓\033[0m %s\n' "$1"; }
no(){ FAIL=$((FAIL+1)); printf '  \033[31m✗\033[0m %s\n' "$1"; printf '      expected: %s\n      actual:   %s\n' "$2" "$3"; }
eq(){ if [ "$2" = "$3" ]; then ok "$1"; else no "$1" "$2" "$3"; fi; }
describe(){ printf '\n\033[1m%s\033[0m\n' "$1"; }

SANDBOX="$(mktemp -d 2>/dev/null || mktemp -d -t wdtest)"
trap 'rm -rf "$SANDBOX"' EXIT
export HOME="$SANDBOX"
mkdir -p "$SANDBOX/logs" "$SANDBOX/ring/cam1"
cat > "$SANDBOX/cam1.env" <<'ENV'
RTSP_MAIN="rtsp://user:pass@192.168.101.3:554/live/ch0"
OUT_DIR="/sdcard/Movies/Camaras/Camara1"
ENV

export WATCH_LOG="$SANDBOX/logs/watchdog.log" RING_BASE="$SANDBOX/ring"
export DET_BLIND_KICK=300 DET_KICK_MAX=3 DET_KICK_BACKOFF=3
# shellcheck source=../watchdog.sh
WATCHDOG_LIB=1 . "$SUT" || { echo "FATAL: could not source $SUT"; exit 1; }

# --- controllable world --------------------------------------------------------------------------
NOW=1786900000; SESSION_ALIVE=1; PING_OK=0; KILLED=0; REVIVED=0; EVENTS=""
log(){ :; }
sleep(){ :; }   # the ladder sleeps 1s before reviving; tests must not pay for it 720 times
date(){ if [ "${1:-}" = "+%s" ]; then echo "$NOW"; else command date "$@"; fi; }
# Models a real tmux closely enough to matter: killing a session makes it STOP EXISTING, so a test
# can tell "killed and revived" apart from "killed and left down" — the distinction that the live
# run on 2026-08-16 exposed and the original stub could not express.
tmux(){
  case "${1:-}" in
    has-session)  [ "$SESSION_ALIVE" = 1 ] && return 0 || return 1 ;;
    kill-session) KILLED=$((KILLED+1)); SESSION_ALIVE=0; return 0 ;;
    new-session)  REVIVED=$((REVIVED+1)); SESSION_ALIVE=1; return 0 ;;
  esac
  return 0
}
ping(){ return "$PING_OK"; }   # 0 = reachable
wlog_event(){ EVENTS="${EVENTS}${EVENTS:+,}$2"; }
# Stubbed so a cycle costs no forks: the real one sources an env file and pipes through sed, and the
# ceiling test runs 720 cycles. Keeping it real made the suite take minutes and hid a hang.
cam_env_var(){ case "$2" in RTSP_MAIN) echo "rtsp://u:p@192.168.101.3:554/live/ch0";; *) echo "";; esac; }

set_det(){ printf '%s %s\n' "$1" "${2:-0}" > "$SANDBOX/ring/cam1/.det_state"; }
reset_world(){
  KILLED=0; REVIVED=0; EVENTS=""; PING_OK=0; SESSION_ALIVE=1; NOW=1786900000
  DET_KICKS=(); DET_LAST=()
}
kick(){ kick_blind_detector cam1 "$SANDBOX/ring/cam1"; }

# =================================================================================================
describe "the ladder does nothing when it should do nothing"
# =================================================================================================
reset_world; set_det OK 0; kick
eq "healthy detector → no restart"            "0" "$KILLED"

reset_world; set_det DOWN $((NOW - 120)); kick
eq "blind 120s (< 300s threshold) → no restart" "0" "$KILLED"

reset_world; set_det DOWN $((NOW - 600)); SESSION_ALIVE=0; kick
eq "session already dead → no restart"        "0" "$KILLED"

reset_world; rm -f "$SANDBOX/ring/cam1/.det_state"; kick
eq "no .det_state at all → no restart"        "0" "$KILLED"
set_det OK 0

# =================================================================================================
describe "guard 1 — an unreachable camera is not a local fault"
# =================================================================================================
reset_world; set_det DOWN $((NOW - 600)); PING_OK=1; kick
eq "blind + camera does not ping → no restart" "0" "$KILLED"
eq "…and it is recorded as skipped"            "skipped" "$EVENTS"

reset_world; set_det DOWN $((NOW - 600)); kick
eq "blind + camera pings → restarts"           "1" "$KILLED"
eq "…and it is recorded as a restart"          "restart" "$EVENTS"

# =================================================================================================
describe "the restart must not leave a recording hole"
# =================================================================================================
# Found in production, not here: the first version killed the session and left the revival to the
# next supervision cycle, so the camera stopped recording for a full INTERVAL (killed 20:58:26,
# still down at 21:00:23). A watchdog that stops recording in order to fix detection is a bad trade.
reset_world; set_det DOWN $((NOW - 600)); kick
eq "session is revived in the SAME pass"        "1" "$REVIVED"
eq "…and is alive when the pass ends"           "1" "$SESSION_ALIVE"

# =================================================================================================
describe "guard 2 — backoff grows as 300s x 3^n between attempts"
# =================================================================================================
# The wait AFTER attempt n is DET_BLIND_KICK * DET_KICK_BACKOFF^n, so attempts land at roughly
# 5 min, 20 min and 65 min into an outage: three tries spread over about an hour, then the ceiling.
reset_world; set_det DOWN $((NOW - 600))
kick                                    # attempt 1 at t=0
NOW=$((NOW + 60));  kick                # 60s later — far too soon
eq "second attempt 60s later is suppressed"   "1" "$KILLED"
NOW=$((NOW + 500)); kick                # 560s since attempt 1; the wait is 900
eq "second attempt still waiting at 560s"     "1" "$KILLED"
NOW=$((NOW + 400)); kick                # 960s since attempt 1 (>= 900)
eq "second attempt after the 900s wait"       "2" "$KILLED"
NOW=$((NOW + 400)); kick                # 400s since attempt 2; the wait is now 2700
eq "third attempt still waiting at 400s"      "2" "$KILLED"
NOW=$((NOW + 2400)); kick               # 2800s since attempt 2 (>= 2700)
eq "third attempt after the 2700s wait"       "3" "$KILLED"

# =================================================================================================
describe "guard 3 — the ceiling (this is the restart-loop test)"
# =================================================================================================
# A camera that is dead but still pinging is the exact shape of 2026-08-16. Left unguarded, the
# ladder would restart the NVR every INTERVAL for as long as the fault lasts. Simulate a full day.
reset_world; set_det DOWN $((NOW - 600))
for _ in $(seq 1 720); do kick; NOW=$((NOW + 120)); done   # 720 cycles x 120s = 24 hours
eq "24h of a blind, pinging camera → at most DET_KICK_MAX restarts" "3" "$KILLED"
case "$EVENTS" in *giveup*) ok "…and it stands down explicitly";; *) no "…and it stands down explicitly" "contains giveup" "$EVENTS";; esac
eq "give-up is logged once, not every cycle" "1" "$(printf '%s' "$EVENTS" | tr ',' '\n' | grep -c giveup)"

# =================================================================================================
describe "guard 4 — recovery closes the episode"
# =================================================================================================
reset_world; set_det DOWN $((NOW - 600))
kick; NOW=$((NOW + 950)); kick                # past the 900s wait, so two attempts are used
eq "two restarts used"                        "2" "$KILLED"
set_det OK 0; kick                            # detector comes back
eq "recovery emits a 'recovered' event"       "restart,restart,recovered" "$EVENTS"

# The reset must restore the whole BUDGET, not merely allow one more try. Asserting "a new episode
# restarts at least once" is too weak: the backoff alone permits that even with the counter left
# exhausted, so such a test passes with the reset deleted (caught by mutation-check). Exhaust all
# three attempts, recover, then assert a later outage gets three FRESH ones.
reset_world; set_det DOWN $((NOW - 600))
kick; NOW=$((NOW + 950)); kick; NOW=$((NOW + 2750)); kick
eq "first episode spends its full budget"     "3" "$KILLED"
set_det OK 0; kick                            # recovery closes the episode
NOW=$((NOW + 100000)); set_det DOWN $((NOW - 600)); KILLED=0
kick; NOW=$((NOW + 950)); kick; NOW=$((NOW + 2750)); kick
eq "a NEW episode gets a full budget again"   "3" "$KILLED"

# =================================================================================================
describe "the 2026-08-16 timeline, replayed"
# =================================================================================================
# Detector went blind at 01:25 and stayed blind until ~15:03 — 13h40m in which the old watchdog did
# nothing at all. Assert the ladder intervenes early, and how much of that outage it would cover.
reset_world; set_det DOWN "$NOW"
BLIND_START=$NOW; FIRST_KICK=0
for _ in $(seq 1 410); do                       # 410 cycles x 120s ≈ 13h40m
  kick
  [ "$KILLED" -ge 1 ] && [ "$FIRST_KICK" = 0 ] && FIRST_KICK=$(( NOW - BLIND_START ))
  NOW=$((NOW + 120))
done
eq "first intervention within ~6 min (was: never)" "360" "$FIRST_KICK"
eq "total restarts over the whole 13h40m outage"   "3"   "$KILLED"

printf '\n\033[1m%d passed, %d failed\033[0m\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
