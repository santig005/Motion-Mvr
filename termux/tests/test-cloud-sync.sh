#!/usr/bin/env bash
# Unit tests for cloud-sync.sh's housekeeping of the event logs.
#
# Since 2026-09-26 (multi-camera step 1) each camera keeps its own <cam>/events.jsonl, and the root
# events.jsonl is the system log. cloud-sync is still the ONLY trimmer of all of them — the keepers and
# the watchdog only ever append — so if it forgot a camera's log, that file would grow without bound
# (and be re-uploaded whole on every refresh cycle over a weak link).
#
# Run: bash termux/tests/test-cloud-sync.sh
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUT="${SUT_OVERRIDE:-$HERE/../cloud-sync.sh}"
PASS=0; FAIL=0

ok(){ PASS=$((PASS+1)); printf '  \033[32m✓\033[0m %s\n' "$1"; }
no(){ FAIL=$((FAIL+1)); printf '  \033[31m✗\033[0m %s\n' "$1"; printf '      expected: %s\n      actual:   %s\n' "$2" "$3"; }
eq(){ if [ "$2" = "$3" ]; then ok "$1"; else no "$1" "$2" "$3"; fi; }
describe(){ printf '\n\033[1m%s\033[0m\n' "$1"; }

SANDBOX="$(mktemp -d 2>/dev/null || mktemp -d -t cstest)"
trap 'rm -rf "$SANDBOX"' EXIT
export HOME="$SANDBOX"                                    # no ~/cloud.env here: defaults + the exports below
export CAMERAS_DIR="$SANDBOX/Camaras" SYNC_LOG="$SANDBOX/logs/cloud-sync.log" EVENTS_MAX_KB=1
mkdir -p "$CAMERAS_DIR/Camara1" "$CAMERAS_DIR/Camara2"

# shellcheck source=../cloud-sync.sh
CLOUD_SYNC_LIB=1 . "$SUT" || { echo "FATAL: could not source $SUT"; exit 1; }

fill(){ # $1=file $2=lines — ~60 bytes each, so 100 lines is well past the 1 KB cap
  local i; : > "$1"
  for i in $(seq 1 "$2"); do printf '{"ts":%d,"cam":"Camara1","svc":"segmenter","ev":"drop"}\n' "$i" >> "$1"; done
}

# =================================================================================================
describe "trim_events — the system log AND every camera's log"
# =================================================================================================
fill "$CAMERAS_DIR/events.jsonl" 100
fill "$CAMERAS_DIR/Camara1/events.jsonl" 100
fill "$CAMERAS_DIR/Camara2/events.jsonl" 5            # under the cap: must be left alone
trim_events
eq "root system log trimmed to its newest half"  "50" "$(wc -l < "$CAMERAS_DIR/events.jsonl")"
eq "a camera's log is trimmed too"               "50" "$(wc -l < "$CAMERAS_DIR/Camara1/events.jsonl")"
eq "a small camera log is left untouched"        "5"  "$(wc -l < "$CAMERAS_DIR/Camara2/events.jsonl")"
eq "the NEWEST lines survive"                    '{"ts":100,"cam":"Camara1","svc":"segmenter","ev":"drop"}' \
   "$(tail -1 "$CAMERAS_DIR/Camara1/events.jsonl")"
eq "no partial JSON line is left at the head"    '{"ts":51,"cam":"Camara1","svc":"segmenter","ev":"drop"}' \
   "$(head -1 "$CAMERAS_DIR/Camara1/events.jsonl")"

rm -f "$CAMERAS_DIR/events.jsonl"
fill "$CAMERAS_DIR/Camara2/events.jsonl" 100
trim_events; rc=$?
eq "a missing system log does not stop the camera logs" "50" "$(wc -l < "$CAMERAS_DIR/Camara2/events.jsonl")"
eq "…and trim_events still succeeds"                    "0"  "$rc"

# =================================================================================================
describe "fav_basenames — favourites spare exactly their own clip"
# =================================================================================================
favs=$(printf '%s' '["mt_20260601_143200","mt_20260926_125715_Camara2","mt_20260926_125715_Camara2"]' | fav_basenames | tr '\n' ' ')
eq "legacy + tagged names, deduplicated" "mt_20260601_143200 mt_20260926_125715_Camara2 " "$favs"
# The tag must survive: cut back to the bare timestamp, a Camara2 star would also spare Camara1's
# same-second clip from the 30-day purge.
case "$favs" in *"mt_20260926_125715 "*) no "the camera tag is kept" "…_Camara2" "$favs" ;;
                *) ok "the camera tag is kept" ;; esac

printf '\n\033[1m%d passed, %d failed\033[0m\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
