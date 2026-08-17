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
  's/! ping -c1 -W2 "\$host" >\/dev\/null 2>&1/false/s'

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

echo
if [ "$SURVIVED" -eq 0 ]; then
  printf '\033[1mAll mutants killed — every fix and every guard is covered by a test that fails without it.\033[0m\n'
else
  printf '\033[1;31m%d mutant(s) survived.\033[0m\n' "$SURVIVED"
fi
[ "$SURVIVED" -eq 0 ]
