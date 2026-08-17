#!/usr/bin/env bash
# Unit tests for the NVR's clip-boundary logic (termux/record-preroll.sh).
#
# WHY THESE EXIST, AND WHY THEY ARE PLAIN BASH
# The decision logic in record-preroll.sh has caused every clip-shaped incident so far: the false
# gap-split of 2026-07-20, the 0-second clips of 2026-08-14, the tail-trim that silently never
# trimmed. All of it is arithmetic over segment timestamps — trivially testable, and until now
# validated only by watching the phone. bats would be the conventional choice; it is not installed
# on the dev machine, and a test suite that cannot be run is worth nothing, so this is a ~40-line
# runner in the bash that is already there. It needs no network, no camera, no ffmpeg and no phone.
#
# HOW IT WORKS
# record-preroll.sh is sourced with RECORD_PREROLL_LIB=1, which loads the real functions and stops
# before the loops start. ffprobe/ffmpeg/render_clip are then replaced with shell functions, so each
# test drives the REAL build_clip / profile_motion over a synthetic ring.
#
# Run: bash termux/tests/run-tests.sh
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# SUT_OVERRIDE lets mutation-testing point the suite at a deliberately broken copy, to prove these
# tests can actually fail. See tests/mutation-check.sh.
SUT="${SUT_OVERRIDE:-$HERE/../record-preroll.sh}"
PASS=0; FAIL=0; CURRENT=""

ok(){ PASS=$((PASS+1)); printf '  \033[32m✓\033[0m %s\n' "$1"; }
no(){ FAIL=$((FAIL+1)); printf '  \033[31m✗\033[0m %s\n' "$1"; printf '      expected: %s\n      actual:   %s\n' "$2" "$3"; }
eq(){ # $1=label $2=expected $3=actual
  if [ "$2" = "$3" ]; then ok "$1"; else no "$1" "$2" "$3"; fi
}
describe(){ CURRENT="$1"; printf '\n\033[1m%s\033[0m\n' "$1"; }

# ---------------------------------------------------------------------------------------------
# Harness: load the real script as a library into a throwaway sandbox.
# ---------------------------------------------------------------------------------------------
SANDBOX="$(mktemp -d 2>/dev/null || mktemp -d -t nvrtest)"
trap 'rm -rf "$SANDBOX"' EXIT

export RTSP_MAIN="rtsp://test/ch0" RTSP_DETECT="rtsp://test/ch1"
export OUT_DIR="$SANDBOX/out" RING_DIR="$SANDBOX/ring" LOG="$SANDBOX/log/cam.log"
export EVENTS_LOG="$SANDBOX/events.jsonl" HEALTH_FILE="$SANDBOX/status.json"
export DAILY_HEALTH="$SANDBOX/daily_health.jsonl" HEALTH_ACC="$SANDBOX/.acc"
export BATTERY_HIST="$SANDBOX/.bat" CAM_ENV="/nonexistent"
export SEG_TIME=4 PREROLL=3 POSTROLL=5 TAIL_PAD=2.5 MIN_CLIP=1.5 DET_FPS=6 YAVG_TH=0.4

# shellcheck source=../record-preroll.sh
RECORD_PREROLL_LIB=1 . "$SUT" || { echo "FATAL: could not source $SUT"; exit 1; }

# Silence the production logger; tests assert on behaviour, not on log noise.
log(){ :; }
log_event(){ :; }

# --- stubs ------------------------------------------------------------------------------------
# Each synthetic segment is a plain file whose *content* is its duration in seconds, or the empty
# string to model the truncated, moov-less file a segmenter drop leaves behind. The stub mirrors
# real ffprobe: it prints nothing at all for an unreadable file.
ffprobe(){
  local f="" a
  for a in "$@"; do f="$a"; done   # the path is always last in this script's ffprobe calls
  [ -f "$f" ] || return 1
  local d; d=$(cat "$f")
  [ -n "$d" ] || return 1
  echo "$d"
}

# render_clip is replaced by a recorder: every rendered clip appends "start:end:segcount" to
# $RENDERED, so a test can assert exactly which clips build_clip decided to produce.
RENDERED=""
render_clip(){ # $1=list $2=first_start $3=clip_start $4=clip_end $5=segcount
  RENDERED="${RENDERED}${RENDERED:+ }$3:$4:$5"
  rm -f "$1"
  return 0
}

# Build a synthetic ring: each argument is "offsetFromBase,duration" (empty duration = truncated).
BASE=1786000000
make_ring(){
  rm -rf "$RING_DIR"; mkdir -p "$RING_DIR"
  local spec off dur name
  for spec in "$@"; do
    off="${spec%%,*}"; dur="${spec#*,}"
    name=$(date -u -d "@$((BASE + off))" "+seg_%Y%m%d_%H%M%S" 2>/dev/null)
    printf '%s' "$dur" > "$RING_DIR/$name.mp4"
  done
}
run_build(){ # $1=clip_start_offset $2=clip_end_offset
  RENDERED=""
  TZ=UTC build_clip "$((BASE + $1))" "$((BASE + $2))" "" >/dev/null 2>&1
  echo "$RENDERED"
}
# Report rendered windows as offsets from BASE, so expectations stay readable.
rel(){ local out="" p s e c; for p in $1; do IFS=: read -r s e c <<<"$p"
         out="${out}${out:+ }$((s - BASE)):$((e - BASE)):$c"; done; echo "$out"; }

# =============================================================================================
describe "seg_epoch — segment filename → epoch"
# =============================================================================================
# TZ-dependent by design (segment names are written with local strftime), so pin it.
eq "parses a well-formed name"      "$(TZ=UTC date -u -d '2026-08-14 18:39:41' +%s)" \
                                    "$(TZ=UTC seg_epoch seg_20260814_183941)"
eq "rejects garbage"                ""  "$(TZ=UTC seg_epoch seg_notatimestamp 2>/dev/null)"

# =============================================================================================
describe "crop_for_width — picks a crop that fits the frame"
# =============================================================================================
METRIC_CROP="760:1296:818:0"; DET_CROP="210:360:226:0"
eq "2K frame → the 2K metric crop"  "760:1296:818:0"  "$(crop_for_width 2304)"
eq "360p frame → the detector crop" "210:360:226:0"   "$(crop_for_width 640)"
eq "tiny frame → no crop at all"    ""                "$(crop_for_width 100)"

# =============================================================================================
describe "build_clip / gap-split — the 2026-07-20 regression"
# =============================================================================================
# Real segments last about one camera GOP (~12 s), NOT SEG_TIME. The original rule compared
# start-to-start against 2*SEG_TIME+4 = 12 s and so read that normal spacing as a dropout, slicing
# roughly one event in five into a stray 1-2 s fragment. The rule now compares the previous
# segment's END to the next one's START, which is immune to GOP jitter.
make_ring "0,12" "12,12" "24,12" "36,12"
eq "healthy ~12s GOP spacing → ONE clip" \
   "5:40:4" "$(rel "$(run_build 5 40)")"

# A real dropout: nothing recorded between t=12 and t=40 (a 28 s hole).
make_ring "0,12" "40,12" "52,12"
eq "real 28s dropout → TWO clips" \
   "5:12:1 40:60:2" "$(rel "$(run_build 5 60)")"

# The hole is measured end→start, so a run of segments that abut is never split however long it is.
make_ring "0,12" "12,12" "24,12" "36,12" "48,12" "60,12"
eq "six abutting segments → still ONE clip" \
   "5:70:6" "$(rel "$(run_build 5 70)")"

# =============================================================================================
describe "build_clip — 0-second clips (2026-08-14)"
# =============================================================================================
# When a segmenter run drops, the segment ffmpeg was mid-write keeps no moov atom and probes as
# EMPTY. build_clip skips only $newest, so the next run's first segment took over as newest and the
# orphan went into the concat, where ${dseg:-0} made it a ZERO-length segment. That collapsed the
# window to nothing, render_clip floored it at 1 s, and out came a 1-frame ~1.8 KB clip.
make_ring "0,12" "12," "24,12"
# Dropping it leaves a genuine 12 s hole in the footage — the truncated file really did record
# nothing usable — so splitting into two clips is the honest answer. What must NOT happen is the old
# behaviour: a zero-length segment silently collapsing a window into a 1-frame stub.
eq "truncated segment becomes a hole, not a 0-length segment" \
   "5:12:1 24:36:1" "$(rel "$(run_build 5 36)")"

# The exact shape of mt_20260814_183941: the ONLY segment covering the window is the truncated one.
# The correct outcome is no clip at all — not a 1-second stub.
make_ring "0,"
eq "window backed solely by a truncated segment → NO clip" \
   "" "$(rel "$(run_build 5 25)")"

# =============================================================================================
describe "build_clip — MIN_CLIP floor on gap-split slivers"
# =============================================================================================
# A trailing segment that merely grazes the window's edge used to become its own 1 s clip alongside
# the real one. MIN_CLIP was declared in cam.env.example from day one and never read by anything.
make_ring "0,12" "40,12"
eq "sliver run shorter than MIN_CLIP is dropped, real clip kept" \
   "5:12:1" "$(rel "$(run_build 5 41)")"

MIN_CLIP=0.5
eq "same ring, lower MIN_CLIP → the sliver IS kept" \
   "5:12:1 40:41:1" "$(rel "$(run_build 5 41)")"
MIN_CLIP=1.5

# =============================================================================================
describe "profile_motion — window rebasing (the silent tail-trim bug)"
# =============================================================================================
# `-ss` sits after `-i`, so it is an OUTPUT seek: the filtergraph still sees every frame from the
# start of the concat and metadata=print reports pts_time on the CONCAT's timeline. Verified on the
# phone 2026-08-16: `-ss 10 -t 12` printed pts_time 0.167 → 22.0, not 0 → 12.
# The stub replays exactly that shape of output.
# Timestamps are stepped as i/fps rather than by accumulating a decimal, so the fixture lands on
# exact frame times and the assertions below can be exact instead of approximate.
fake_frames(){ # $1=end_t $2=motion_start $3=motion_end  (fps = DET_FPS)
  awk -v e="$1" -v m0="$2" -v m1="$3" -v fps="$DET_FPS" 'BEGIN{
    for (i = 0; i <= e * fps; i++) {
      t = i / fps
      printf "[Parsed_metadata_6 @ 0x0] frame:0 pts:0 pts_time:%.6f\n", t
      v = (t >= m0 && t <= m1) ? 9.5 : 0.0
      printf "[Parsed_metadata_6 @ 0x0] lavfi.signalstats.YAVG=%.3f\n", v
    } }'
}
# Motion from t=12 to t=15 on the concat timeline; the clip window starts at offset=10.
# Relative to the window that is motion from 2 s to 5 s, so the tail must be cut at 5 + 2.5 = 7.5 s.
ffmpeg(){ fake_frames 22 12 15; }
read -r m0 m1 cut _mx _mean _n <<<"$(profile_motion /dev/null 10 12 "")"
eq "m0 is rebased onto the window"      "2.000"  "$m0"
eq "m1 is rebased onto the window"      "5.000"  "$m1"
eq "cut = m1 + TAIL_PAD, window-relative — NOT 17.5" "7.500" "$cut"

# Motion entirely BEFORE the window: it belongs to earlier footage, not to this clip.
ffmpeg(){ fake_frames 22 2 5; }
eq "motion only before the window → NOMOTION" \
   "NOMOTION" "$(profile_motion /dev/null 10 12 "")"

# Sanity: with offset 0 the rebasing is a no-op, so previously-correct clips stay correct.
ffmpeg(){ fake_frames 12 3 6; }
read -r m0 m1 cut _mx _mean _n <<<"$(profile_motion /dev/null 0 12 "")"
eq "offset=0 is unchanged (m1)"   "6.000"  "$m1"
eq "offset=0 is unchanged (cut)"  "8.500"  "$cut"

# =============================================================================================
describe "ring_scan_segment — the fallback detector's sensitivity"
# =============================================================================================
# It must behave like the RTSP detector, not merely "detect something": same DEBOUNCE, so a single
# hot frame is noise rather than an event. Otherwise a fallback that engages during an outage would
# flood the gallery with clips of nothing at exactly the worst moment.
DEBOUNCE=2
ffmpeg(){ fake_frames 12 3 6; }                 # sustained motion, 3s -> 6s
read -r m0 m1 <<<"$(ring_scan_segment /dev/null)"
eq "reports first motion (after debounce)"  "3.167" "$m0"
eq "reports last motion"                    "6.000" "$m1"

ffmpeg(){ fake_frames 12 99 99; }               # nothing over threshold at all
eq "a quiet segment reports nothing"        ""      "$(ring_scan_segment /dev/null)"

# One frame over threshold: below DEBOUNCE=2, so it must NOT open an event.
ffmpeg(){ awk 'BEGIN{ for(i=0;i<=72;i++){ t=i/6
    printf "[m] frame:0 pts:0 pts_time:%.6f\n", t
    printf "[m] lavfi.signalstats.YAVG=%.3f\n", (i==30 ? 9.5 : 0.0) } }'; }
eq "a single hot frame is noise, not motion" ""     "$(ring_scan_segment /dev/null)"

# =============================================================================================
describe "compute_battery_eta — regression + the running-minimum filter"
# =============================================================================================
# 10 %/h discharge from 80 %: the ETA to BATTERY_FLOOR_PCT=5 must be (80-5)/10 h = 450 min.
BATTERY_FLOOR_PCT=5
: > "$BATTERY_HIST"
for i in 0 1 2 3; do echo "$((BASE + i*3600)) $((80 - i*10))" >> "$BATTERY_HIST"; done
read -r rate eta <<<"$(compute_battery_eta 80)"
eq "discharge rate is %/h"        "10.00" "$rate"
eq "ETA extrapolates to the floor" "450"  "$eta"

# A single upward blip (the battery sensor jitters) must not bend the slope.
: > "$BATTERY_HIST"
for i in 0 1 2 3; do echo "$((BASE + i*3600)) $((80 - i*10))" >> "$BATTERY_HIST"; done
echo "$((BASE + 4*3600)) 95" >> "$BATTERY_HIST"
read -r rate _eta <<<"$(compute_battery_eta 50)"
eq "upward blip is filtered out"  "10.00" "$rate"

: > "$BATTERY_HIST"; echo "$BASE 80" >> "$BATTERY_HIST"
eq "a single sample yields no estimate" "" "$(compute_battery_eta 80)"

# =============================================================================================
printf '\n\033[1m%d passed, %d failed\033[0m\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
