#!/usr/bin/env bash
# Mutation check: prove the test suites can actually FAIL.
#
# A green suite means nothing until you have seen it go red for the right reason. This re-introduces
# each fixed bug — and removes each watchdog guard — one at a time, into a throwaway copy, and
# asserts the matching suite rejects it. If a mutant SURVIVES, the test covering it is decorative
# and should be rewritten.
#
# The watchdog mutants matter most. That is the only component that can make the system worse rather
# than merely wrong: without its guards it restarts the NVR in a loop and nothing is ever recorded.
#
# Run: bash termux/tests/mutation-check.sh
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d 2>/dev/null || mktemp -d -t nvrmut)"
trap 'rm -rf "$TMP"' EXIT
SURVIVED=0

mutate(){ # $1=name  $2=source file  $3=test script  $4=perl expression re-introducing the bug
  local name="$1" src="$HERE/../$2" suite="$HERE/$3" expr="$4" mutant="$TMP/$2"
  cp "$src" "$mutant"
  perl -0pi -e "$expr" "$mutant"
  if cmp -s "$src" "$mutant"; then
    printf '  \033[33m?\033[0m %-48s MUTATION DID NOT APPLY (pattern drifted)\n' "$name"
    SURVIVED=$((SURVIVED+1)); return
  fi
  if SUT_OVERRIDE="$mutant" bash "$suite" >/dev/null 2>&1; then
    printf '  \033[31m✗\033[0m %-48s SURVIVED — no test covers this\n' "$name"
    SURVIVED=$((SURVIVED+1))
  else
    printf '  \033[32m✓\033[0m %-48s killed\n' "$name"
  fi
}

printf '\033[1mSegmenter — re-introducing each 2026-08-16 bug\033[0m\n'

mutate "unreadable ring segment → 0-length" record-preroll.sh run-tests.sh \
  's/if ! awk -v d="\$\{dseg:-0\}" .BEGIN\{exit !\(d\+0 > 0\.1\)\}.; then\n.*?\n.*?fi\n//s'

mutate "MIN_CLIP floor on slivers" record-preroll.sh run-tests.sh \
  's/if awk -v a="\$run_cstart" -v b="\$run_cend" -v m="\$MIN_CLIP" .BEGIN\{exit !\(\(b-a\) < m\)\}.; then\n.*?\n.*?\n.*?fi\n//s'

mutate "profile_motion window rebasing" record-preroll.sh run-tests.sh \
  's/if\(t < off\) next;.*?\n\s*rt=t-off;.*?\n/rt=t;\n/s'

mutate "compute_battery_eta reads its file" record-preroll.sh run-tests.sh \
  's/\}. "\$BATTERY_HIST" <\/dev\/null\n\}/}'"'"'\n}/s'

printf '\n\033[1mWatchdog — removing each recovery guard\033[0m\n'

# Guard 1: without the ping check it restarts the NVR over a camera that is simply switched off.
mutate "guard 1: ping before restarting" watchdog.sh test-watchdog.sh \
  's/! host_pings "\$host" 5/false/s'

# The 2026-08-26 bug: if recovery does not have to HOLD, the fresh session's own non-DOWN state
# closes the episode, the attempt counter resets, and the ladder restarts the NVR forever at
# "attempt 1" — with guards 2 and 3 both alive but unreachable.
mutate "guard 4b: recovery must be sustained" watchdog.sh test-watchdog.sh   's/\[ "\$\(\( now - \$\{DET_WELL\[\$cam\]\} \)\)" -lt "\$DET_RECOVER_SECS" \] && return 0//s'

# Guard 2: without backoff it restarts every INTERVAL — the restart loop.
mutate "guard 2: backoff between attempts" watchdog.sh test-watchdog.sh \
  's/\[ "\$\(\( now - last \)\)" -lt "\$wait_s" \] && return 0/:/s'

# Guard 3: without the ceiling it never stands down, restarting for as long as the fault lasts.
mutate "guard 3: ceiling on attempts" watchdog.sh test-watchdog.sh \
  's/\[ "\$n" -ge "\$DET_KICK_MAX" \]/[ "\$n" -ge 999999 ]/s'

# Guard 4: without the reset a later, unrelated outage inherits an exhausted counter and is ignored.
mutate "guard 4: reset on recovery" watchdog.sh test-watchdog.sh \
  's/DET_KICKS\[\$cam\]=0; DET_LAST\[\$cam\]=0/:/s'

# Guard 5: without the immediate revive the camera stops recording for a whole INTERVAL.
mutate "guard 5: revive in the same pass" watchdog.sh test-watchdog.sh \
  's/  sleep 1\n  ensure_session "\$cam" "\$\(cam_cmd "\$cam"\)"\n//s'

printf '\n\033[1mSegmenter stall + camera power-cycle (2026-09-25)\033[0m\n'

# Without the grace every run is killed during its own RTSP handshake: a reconnect loop.
mutate "stall: connect grace" record-preroll.sh run-tests.sh \
  's/  \[ "\$\(\(now - t0\)\)" -ge "\$SEG_STALL_SECS" \] \|\| return 1\n//s'

# The original bug: liveness judged only by "ffmpeg is still running".
mutate "stall: judge by the ring, not the process" record-preroll.sh run-tests.sh \
  's/(seg_stalled\(\)\{\n  local t0="\$1" now="\$2" newest m=0\n)/$1  return 1\n/s'

# The gap read from RAM instead of disk = a restart re-arms it = power-cycle loop.
mutate "power-cycle: persisted gap" record-preroll.sh run-tests.sh \
  's/  if \[ "\$\(\(now - rlast\)\)" -lt "\$REBOOT_EVERY_SECS" \]; then return 1; fi\n//s'

mutate "power-cycle: daily cap" record-preroll.sh run-tests.sh \
  's/  if \[ "\$rcount" -ge "\$REBOOT_MAX_PER_DAY" \]; then/  if false; then/s'

# Power-cycling an unreachable camera hides power/network faults behind pointless relay clicks.
mutate "power-cycle: only when wedged" record-preroll.sh run-tests.sh \
  's/(log_event recording unreachable "no ping \$\{downfor\}s"\n)/$1    power_cycle_camera "\$downfor"\n/s'

printf '\n\033[1mPer-camera telemetry (multi-camera step 1, 2026-09-26)\033[0m\n'

# Back at CAMERAS_DIR root, two keepers rewrite the same file and drop each other's lines.
mutate "daily_health back at the shared root" record-preroll.sh run-tests.sh \
  's/DAILY_HEALTH="\$\{DAILY_HEALTH:-\$OUT_DIR\/daily_health\.jsonl\}"/DAILY_HEALTH="\${DAILY_HEALTH:-\$(dirname "\$OUT_DIR")\/daily_health.jsonl}"/s'

mutate "wifi.jsonl back at the shared root" record-preroll.sh run-tests.sh \
  's/WIFI_LOG="\$\{WIFI_LOG:-\$OUT_DIR\/wifi\.jsonl\}"/WIFI_LOG="\${WIFI_LOG:-\$(dirname "\$OUT_DIR")\/wifi.jsonl}"/s'

# The pre-fix watchdog: the env name as the camera id, i.e. a phantom second camera in the app.
mutate "watchdog: env name as camera id" watchdog.sh test-watchdog.sh \
  's/"\$\(date \+%s\)" "\$label" "\$2" "\$3"/"\$(date +%s)" "\$1" "\$2" "\$3"/s'

mutate "watchdog: event into the root log" watchdog.sh test-watchdog.sh \
  's/ev_log="\$out\/events\.jsonl"/ev_log="\$(dirname "\$out")\/events.jsonl"/s'

# Only the root log trimmed: every camera's log grows forever.
mutate "trim: only the system log" cloud-sync.sh test-cloud-sync.sh \
  's/for f in "\$EVENTS_LOG" "\$CAMERAS_DIR"\/\*\/events\.jsonl; do/for f in "\$EVENTS_LOG"; do/s'

printf '\n\033[1mCamera-tagged clip names (multi-camera F1, 2026-09-26)\033[0m\n'

# A bare timestamp: two cameras' same-second clips collide in every name-keyed store of the app.
mutate "clip name without the camera tag" record-preroll.sh run-tests.sh \
  's/printf .mt_%s_%s. "\$1" "\$CLIP_TAG"/printf "mt_%s" "\$1"/s'

mutate "metrics datetime carries the tag" record-preroll.sh run-tests.sh \
  's/\$\{dt:0:15\}/\$dt/s'

# Cut back to the timestamp, one camera's star would spare every camera's same-second clip.
mutate "favourites drop the camera tag" cloud-sync.sh test-cloud-sync.sh \
  's/\(_\[A-Za-z0-9-\]\+\)\?//s'

printf '\n\033[1mUpload queue (multi-camera B1, 2026-09-26)\033[0m\n'

# Markers never removed: every cycle re-uploads the whole history of the queue.
mutate "queue: success removes the markers" cloud-sync.sh test-cloud-sync.sh \
  's/      for m in \$batch; do rm -f "\$q\/\$m"; done\n//s'

# Counting transient failures: one Drive outage would dead-letter the whole queue.
mutate "queue: outages never dead-letter" cloud-sync.sh test-cloud-sync.sh \
  's/if \[ "\$reason" = error \]; then/if true; then/s'

# No dead-letter: one permanently failing clip blocks the head of the queue forever.
mutate "queue: a stuck clip is set aside" cloud-sync.sh test-cloud-sync.sh \
  's/\[ "\$tries" -ge "\$QUEUE_MAX_TRIES" \]/[ "\$tries" -ge 999999 ]/s'

# Without the queue check every scan/queue race is reported as a leak: an audit nobody can trust.
mutate "backstop: a race is not a miss" cloud-sync.sh test-cloud-sync.sh \
  's/\n[^\n]*grep -q[^\n]*&& continue//s'

# Without the time prefix the order is by camera name, not by who finished first.
mutate "producer: marker leads with finish time" record-preroll.sh run-tests.sh \
  's/m="\$\(date \+%s%N\)\.\$\{CLIP_TAG\}\./m="\${CLIP_TAG}./s'

echo
if [ "$SURVIVED" -eq 0 ]; then
  printf '\033[1mAll mutants killed — every fix and every guard is covered by a test that fails without it.\033[0m\n'
else
  printf '\033[1;31m%d mutant(s) survived.\033[0m\n' "$SURVIVED"
fi
[ "$SURVIVED" -eq 0 ]
