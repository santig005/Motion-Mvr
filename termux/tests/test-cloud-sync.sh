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
describe "drain_queue — first finished, first uploaded, across cameras (multi-camera B1)"
# =================================================================================================
export UPLOAD_QUEUE="$SANDBOX/queue"
log(){ :; }; log_event(){ :; }
# rclone stub: records the upload order it was handed, and fails on demand.
RCLONE_RC=0; RCLONE_OUT=""; CALLS=0; SENT=""
rclone(){
  local a prev=""
  CALLS=$((CALLS + 1))
  for a in "$@"; do [ "$prev" = "--files-from-raw" ] && SENT="${SENT}$(tr '\n' ' ' < "$a")"; prev="$a"; done
  [ -n "$RCLONE_OUT" ] && echo "$RCLONE_OUT"
  return "$RCLONE_RC"
}
day="$CAMERAS_DIR/Camara1/2026/09/26"; day2="$CAMERAS_DIR/Camara2/2026/09/26"; mkdir -p "$day" "$day2"
mark(){ # $1=ns  $2=clip path — the same shape record-preroll's enqueue_upload writes
  local cam; cam=$(basename "$(dirname "$(dirname "$(dirname "$(dirname "$2")")")")")
  mkdir -p "$UPLOAD_QUEUE"; printf '%s\n' "$2" > "$UPLOAD_QUEUE/$1.$cam.$(basename "$2" .mp4)"
}
qlen(){ ls -1 "$UPLOAD_QUEUE" 2>/dev/null | grep -vc '^\.'; }
reset_q(){ rm -rf "$UPLOAD_QUEUE"; CALLS=0; SENT=""; RCLONE_RC=0; RCLONE_OUT=""; }

reset_q
touch "$day/mt_20260926_100005_Camara1.mp4" "$day/mt_20260926_100005_Camara1.jpg" \
      "$day2/mt_20260926_100001_Camara2.mp4" "$day/mt_20260926_100009_Camara1.mp4"
mark 1790000002000000000 "$day/mt_20260926_100005_Camara1.mp4"     # finished 2nd
mark 1790000001000000000 "$day2/mt_20260926_100001_Camara2.mp4"    # finished 1st, other camera
mark 1790000003000000000 "$day/mt_20260926_100009_Camara1.mp4"     # finished 3rd
drain_queue; rc=$?
eq "uploads in finish order, whichever camera" \
   "Camara2/2026/09/26/mt_20260926_100001_Camara2.mp4 Camara1/2026/09/26/mt_20260926_100005_Camara1.mp4 Camara1/2026/09/26/mt_20260926_100005_Camara1.jpg Camara1/2026/09/26/mt_20260926_100009_Camara1.mp4 " "$SENT"
eq "success empties the queue"                  "0" "$(qlen)"
eq "…and reports success"                        "0" "$rc"

reset_q; mark 1790000001000000000 "$day/mt_20260926_100005_Camara1.mp4"
RCLONE_RC=1; RCLONE_OUT="dial tcp: i/o timeout"
drain_queue; rc=$?
eq "a failed batch stays queued"                "1" "$(qlen)"
eq "…and reports failure"                       "1" "$rc"
for i in 1 2 3 4 5 6 7; do drain_queue; done
eq "a network outage never sets a clip aside"   "1" "$(qlen)"

reset_q; mark 1790000001000000000 "$day/mt_20260926_100005_Camara1.mp4"
RCLONE_RC=1; RCLONE_OUT="some permanent failure"
for i in 1 2 3 4; do drain_queue; done
eq "a failing clip keeps its place for a while" "1" "$(qlen)"
drain_queue
eq "…then is set aside so the head moves on"    "0" "$(qlen)"
eq "…into .deadletter (the scans still upload)" "1" "$(ls -1 "$UPLOAD_QUEUE/.deadletter" | grep -c .)"

reset_q; mark 1790000001000000000 "$day/mt_gone_Camara1.mp4"
drain_queue
eq "a marker whose clip is gone is dropped"     "0" "$(qlen)"
eq "…without calling rclone"                    "0" "$CALLS"

reset_q; QUEUE_BATCH=2
for i in 1 2 3 4 5; do touch "$day/mt_20260926_10000${i}_Camara1.mp4"; mark "179000000${i}000000000" "$day/mt_20260926_10000${i}_Camara1.mp4"; done
drain_queue
eq "a backlog drains in batches"                "3" "$CALLS"
eq "…all of it within the cycle's cap"          "0" "$(qlen)"
QUEUE_BATCH=20

# =================================================================================================
describe "scan_filter — the scans leave queued clips to the queue"
# =================================================================================================
reset_q
mark 1790000001000000000 "$day/mt_20260926_120001_Camara1.mp4"
mark 1790000002000000000 "$day2/mt_20260926_120002_Camara2.mp4"
scan_filter "$SANDBOX/rules"
eq "queued clips are excluded first, then the usual includes" \
   "- mt_20260926_120001_Camara1.* - mt_20260926_120002_Camara2.* + *.mp4 + *.jpg - ** " \
   "$(tr '\n' ' ' < "$SANDBOX/rules")"
reset_q; scan_filter "$SANDBOX/rules"
eq "an empty queue leaves only the includes"  "+ *.mp4 + *.jpg - ** " "$(tr '\n' ' ' < "$SANDBOX/rules")"

# =================================================================================================
describe "backstop_audit — a clip the queue missed is reported, a race is not"
# =================================================================================================
reset_q; mkdir -p "$UPLOAD_QUEUE/.deadletter"
mark 1790000001000000000 "$day/mt_20260926_110002_Camara1.mp4"                   # still queued: scan won a race
printf '%s\n' "$day/mt_20260926_110003_Camara1.mp4" > "$UPLOAD_QUEUE/.deadletter/1790000002000000000.Camara1.mt_20260926_110003_Camara1"
out="$SANDBOX/scan.out"
cat > "$out" <<'OUT'
2026/09/26 22:52:39 INFO  : mt_20260926_110001_Camara1.mp4: Copied (new)
2026/09/26 22:52:39 INFO  : mt_20260926_110001_Camara1.jpg: Copied (new)
2026/09/26 22:52:40 INFO  : mt_20260926_110002_Camara1.mp4: Copied (new)
2026/09/26 22:52:41 INFO  : Camara1/2026/09/26/mt_20260926_110003_Camara1.mp4: Copied (replaced existing)
2026/09/26 22:52:42 INFO  : mt_20260926_110004.mp4: Copied (new)
OUT
LOGGED=""; EVENTS=""
log(){ LOGGED="${LOGGED}$*|"; }; log_event(){ EVENTS="${EVENTS}$2/$3: $4|"; }
backstop_total=0
backstop_audit "$out" "fast scan"
eq "exactly the clips the queue missed are counted" "2" "$backstop_total"
case "$LOGGED" in *"110001_Camara1: it was NOT"*) ok "a missed clip is logged by name" ;;
                  *) no "a missed clip is logged by name" "…110001_Camara1…" "$LOGGED" ;; esac
case "$LOGGED" in *"110002"*) no "a clip still queued is not flagged (race)" "no 110002" "$LOGGED" ;;
                  *) ok "a clip still queued is not flagged (race)" ;; esac
case "$LOGGED" in *"110004"*) no "a pre-queue (untagged) clip is not flagged" "no 110004" "$LOGGED" ;;
                  *) ok "a pre-queue (untagged) clip is not flagged" ;; esac
case "$LOGGED" in *"110003_Camara1 (set aside)"*) ok "a dead-lettered clip is labelled as such" ;;
                  *) no "a dead-lettered clip is labelled as such" "…110003_Camara1 (set aside)…" "$LOGGED" ;; esac
case "$EVENTS" in "sync/backstop: fast scan uploaded 2 clip(s)"*) ok "one sync/backstop event per scan" ;;
                  *) no "one sync/backstop event per scan" "sync/backstop: fast scan uploaded 2…" "$EVENTS" ;; esac
LOGGED=""; EVENTS=""; backstop_audit "$SANDBOX/does-not-exist" "self-heal"
eq "a clean scan records nothing" "" "$EVENTS"
log(){ :; }; log_event(){ :; }

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
