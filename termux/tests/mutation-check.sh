#!/usr/bin/env bash
# Mutation check: prove the test suite can actually FAIL.
#
# A green suite means nothing until you have seen it go red for the right reason. This re-introduces
# each of the four bugs fixed on 2026-08-16, one at a time, into a throwaway copy of
# record-preroll.sh, and asserts that run-tests.sh rejects it. If a mutant survives, the test
# covering it is decorative and should be rewritten.
#
# Run: bash termux/tests/mutation-check.sh
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/../record-preroll.sh"
TMP="$(mktemp -d 2>/dev/null || mktemp -d -t nvrmut)"
trap 'rm -rf "$TMP"' EXIT
SURVIVED=0

mutate(){ # $1=name  $2=perl expression that re-introduces the bug
  local name="$1" expr="$2" mutant="$TMP/mutant.sh"
  cp "$SRC" "$mutant"
  perl -0pi -e "$expr" "$mutant"
  if cmp -s "$SRC" "$mutant"; then
    printf '  \033[33m?\033[0m %-46s MUTATION DID NOT APPLY (pattern drifted)\n' "$name"
    SURVIVED=$((SURVIVED+1)); return
  fi
  if SUT_OVERRIDE="$mutant" bash "$HERE/run-tests.sh" >/dev/null 2>&1; then
    printf '  \033[31m✗\033[0m %-46s SURVIVED — no test covers this\n' "$name"
    SURVIVED=$((SURVIVED+1))
  else
    printf '  \033[32m✓\033[0m %-46s killed\n' "$name"
  fi
}

printf '\033[1mMutation check — re-introducing each 2026-08-16 bug\033[0m\n'

# 1. The truncated-segment guard: treat an unreadable segment as zero-length again.
mutate "unreadable ring segment → 0-length" \
  's/if ! awk -v d="\$\{dseg:-0\}" .BEGIN\{exit !\(d\+0 > 0\.1\)\}.; then\n.*?\n.*?fi\n//s'

# 2. The MIN_CLIP floor: emit gap-split slivers again.
mutate "MIN_CLIP floor on slivers" \
  's/if awk -v a="\$run_cstart" -v b="\$run_cend" -v m="\$MIN_CLIP" .BEGIN\{exit !\(\(b-a\) < m\)\}.; then\n.*?\n.*?\n.*?fi\n//s'

# 3. The profiler rebase: report pts_time on the concat timeline again.
mutate "profile_motion window rebasing" \
  's/if\(t < off\) next;.*?\n\s*rt=t-off;.*?\n/rt=t;\n/s'

# 4. compute_battery_eta: read stdin instead of the history file again.
mutate "compute_battery_eta reads its file" \
  's/\}. "\$BATTERY_HIST" <\/dev\/null\n\}/}'"'"'\n}/s'

echo
if [ "$SURVIVED" -eq 0 ]; then
  printf '\033[1mAll mutants killed — every fix is covered by a test that fails without it.\033[0m\n'
else
  printf '\033[1;31m%d mutant(s) survived.\033[0m\n' "$SURVIVED"
fi
[ "$SURVIVED" -eq 0 ]
