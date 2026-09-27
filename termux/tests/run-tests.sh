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
jf(){ printf '%s' "$2" | grep -o "\"$1\":[0-9]*" | head -1 | cut -d: -f2; }   # read one int from a JSON line

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
describe "sample_wifi — Wi-Fi telemetry (cache for status.json + capped wifi.jsonl)"
# =============================================================================================
# read_rssi shells out via `timeout`, which can't see a shell-function stub, so the radio read is
# faked at the read_rssi seam. What matters here is the NEW logic: the sample is cached for
# write_status to emit on every write (incl. the down transition) AND appended to a bounded series.
export WIFI_LOG="$SANDBOX/wifi.jsonl"; : > "$WIFI_LOG"
read_rssi(){ echo "-68 2412"; }
read_link(){ return 1; }                 # no ping here: the link half is covered below
LAST_RSSI=""; LAST_WIFI_FREQ=""; LAST_RSSI_TS=0
sample_wifi
eq "caches the rssi for write_status"   "-68"   "$LAST_RSSI"
eq "caches the band frequency"          "2412"  "$LAST_WIFI_FREQ"
eq "appends exactly one line"           "1"     "$(wc -l < "$WIFI_LOG")"
eq "the line is the expected JSON"      '{"ts":TS,"cam":"out","rssi":-68,"freq_mhz":2412}' \
   "$(sed -E 's/"ts":[0-9]+/"ts":TS/' "$WIFI_LOG")"

# Capping: once the file is a margin past WIFI_MAX_LINES, one more sample trims it to the newest cap.
WIFI_MAX_LINES=5; : > "$WIFI_LOG"
for i in $(seq 1 300); do echo "{\"ts\":$i}" >> "$WIFI_LOG"; done
sample_wifi                              # 301 lines > cap+200 -> trim to newest 5
eq "wifi.jsonl trimmed to the cap"      "5"     "$(wc -l < "$WIFI_LOG")"
WIFI_MAX_LINES=2000

# =============================================================================================
describe "parse_ping / link probe — the RSSI Android freezes while the screen is off"
# =============================================================================================
# 2026-09-22: the RSSI read a flat −68 for 53h and a "normal" −72 through a night where the link lost
# 5% of packets at a 368ms p90. The probe pings the camera instead. Fixtures are real output of the
# NVR phone's /system/bin/ping (iputils).
PING_OK='PING 192.168.101.2 (192.168.101.2) 56(84) bytes of data.
64 bytes from 192.168.101.2: icmp_seq=1 ttl=64 time=2.69 ms
64 bytes from 192.168.101.2: icmp_seq=2 ttl=64 time=4.77 ms
64 bytes from 192.168.101.2: icmp_seq=3 ttl=64 time=3.98 ms

--- 192.168.101.2 ping statistics ---
3 packets transmitted, 3 received, 0% packet loss, time 401ms
rtt min/avg/max/mdev = 2.698/3.817/4.772/0.857 ms'
eq "clean link: loss, median, p90"      "0 4 5"   "$(printf '%s\n' "$PING_OK" | parse_ping)"

# A bad-night sample: 18 replies out of 20, most fast, a slow tail. Sorted, the median is the 9th
# (10ms) and the p90 the 17th (ceil(0.9*18)) — the tail is what the median alone would hide.
PING_BAD=$( for t in 3 4 5 6 7 8 9 9 10 11 12 14 20 45 90 370 368 1044; do
              echo "64 bytes from 192.168.101.2: icmp_seq=1 ttl=64 time=$t ms"; done
            echo "20 packets transmitted, 18 received, 10% packet loss, time 3811ms" )
eq "lossy link: loss, median, p90"      "10 10 370" "$(printf '%s\n' "$PING_BAD" | parse_ping)"

PING_DEAD='PING 192.168.101.250 (192.168.101.250) 56(84) bytes of data.

--- 192.168.101.250 ping statistics ---
3 packets transmitted, 0 received, 100% packet loss, time 405ms'
eq "no replies: 100% and no latency"    "100 - -" "$(printf '%s\n' "$PING_DEAD" | parse_ping)"
# With errors iputils inserts "+N errors" before the loss; the % token must still be found.
eq "tolerates '+N errors'"              "100 - -" \
   "$(echo '5 packets transmitted, 0 received, +5 errors, 100% packet loss, time 4005ms' | parse_ping)"
parse_ping < /dev/null >/dev/null; eq "no summary line -> fails" "1" "$?"

: > "$WIFI_LOG"
read_link(){ echo "10 10 370"; }
LAST_LINK_LOSS=""; LAST_LINK_MED="-"; LAST_LINK_P90="-"; LAST_LINK_TS=0
sample_wifi
eq "caches the link sample"             "10 10 370" "$LAST_LINK_LOSS $LAST_LINK_MED $LAST_LINK_P90"
eq "line carries rssi AND link"         '{"ts":TS,"cam":"out","rssi":-68,"freq_mhz":2412,"link_loss_pct":10,"link_med_ms":10,"link_p90_ms":370}' \
   "$(sed -E 's/"ts":[0-9]+/"ts":TS/' "$WIFI_LOG")"

# The two reads are independent: a dead termux-api must not silence the ping series (the old code
# returned before writing anything when the RSSI read failed).
: > "$WIFI_LOG"
read_rssi(){ return 1; }
read_link(){ echo "100 - -"; }
sample_wifi
eq "link-only line, no latency at 100%" '{"ts":TS,"cam":"out","link_loss_pct":100}' \
   "$(sed -E 's/"ts":[0-9]+/"ts":TS/' "$WIFI_LOG")"

: > "$WIFI_LOG"
read_link(){ return 1; }
sample_wifi
eq "both reads failing writes nothing"  "0" "$(wc -l < "$WIFI_LOG")"
read_rssi(){ echo "-68 2412"; }

# status.json gets the same fields, from the cache, and only while the sample is fresh.
LAST_LINK_LOSS=5; LAST_LINK_MED=4; LAST_LINK_P90=60; LAST_LINK_TS=$(date +%s)
write_status 1
eq "status.json carries the link"       "5 4 60" \
   "$(jf link_loss_pct "$(cat "$HEALTH_FILE")") $(jf link_med_ms "$(cat "$HEALTH_FILE")") $(jf link_p90_ms "$(cat "$HEALTH_FILE")")"
LAST_LINK_TS=$(( $(date +%s) - WIFI_SAMPLE_SECS * 3 - 10 ))
write_status 1
eq "a stale link sample is not published" "" "$(jf link_loss_pct "$(cat "$HEALTH_FILE")")"

# =============================================================================================
describe "per-service health accounting — the 2026-08-26 lesson"
# =============================================================================================
# The old accounting only ever credited an outage at the moment it CLOSED, from an in-RAM watermark.
# The watchdog restarted the keeper during every outage on 2026-08-26, so none of them ever closed:
# daily_health reported rec_down_s=0 and rec_outages=0 on a day with ~43 outages and 10h of no
# footage. These tests pin the two properties that make that impossible to repeat — seconds accrue
# per tick, and the open-outage watermark is persisted — plus the honest handling of "unknown".
# The accumulator is keyed by the LOCAL day, and acc_load deliberately rolls over when the persisted
# day is not today. A hard-coded date therefore passes until midnight and then fails for the right
# reason at the wrong time -- which is exactly what happened the first night this suite existed.
TODAY=$(date +%Y%m%d)

acc_reset $TODAY 1000
acc_tick rec 1 60 1060
acc_tick rec 1 60 1120
eq "up seconds accrue per tick"              "120" "$ACC_rec_UP"
eq "…and nothing is counted as down"         "0"   "$ACC_rec_DOWN"

acc_tick rec 0 60 1180                       # goes down
eq "down seconds accrue too"                 "60"  "$ACC_rec_DOWN"
eq "the outage watermark opens"              "1180" "$ACC_rec_SINCE"
eq "…and is persisted immediately"           "1180" "$(tr ' ' '\n' < "$HEALTH_ACC" | grep '^rec_since=' | cut -d= -f2)"
eq "an OPEN outage is not counted yet"       "0"   "$ACC_rec_OUT"
# An outage still in progress must already report a duration. Reporting "worst: 0s" until it ends
# makes the dashboard least informative exactly while the incident is happening.
date(){ if [ "${1:-}" = "+%s" ]; then echo 1300; else command date "$@"; fi; }
eq "…but its duration is already visible"    "120" "$(svc_worst rec)"
unset -f date

acc_tick rec 0 60 1240
acc_tick rec 1 60 1300                       # recovers
eq "the closed outage is counted once"       "1"   "$ACC_rec_OUT"
eq "…with its full duration as the worst"    "120" "$ACC_rec_WORST"

# Unknown (INIT) is credited to NEITHER side, so uptime% never counts "we had not looked yet" as
# either health or failure — the distinction the old boolean could not express at all.
acc_reset $TODAY 1000
acc_tick det -1 60 1060
eq "unknown accrues no up seconds"           "0"   "$ACC_det_UP"
eq "unknown accrues no down seconds"         "0"   "$ACC_det_DOWN"

# THE regression test: a restart mid-outage must not erase it. acc_load reads the persisted line back.
acc_reset $TODAY 1000
acc_tick rec 0 60 1180                       # outage opens and is saved
ACC_rec_SINCE=999999; ACC_rec_DOWN=999999    # scribble over RAM to prove the reload is from disk
acc_load                                     # <- what a watchdog restart does
eq "a restart mid-outage keeps the watermark" "1180" "$ACC_rec_SINCE"
eq "…and the seconds already banked"          "60"   "$ACC_rec_DOWN"
acc_tick rec 1 60 1480                        # the SAME outage now closes, in a different process
eq "the outage survives the restart"          "1"    "$ACC_rec_OUT"
eq "…charged its true duration, not zero"     "300"  "$ACC_rec_WORST"

# Every service gets the same treatment; the emitted line carries all four.
acc_reset $TODAY 1000
acc_tick rec 1 60 1060; acc_tick det 0 60 1060; acc_tick seg 1 60 1060; acc_tick sync 0 60 1060
LINE=$(daily_line $TODAY 1060 true)
eq "recording up seconds in the line"        "60" "$(jf rec_up_s "$LINE")"
eq "detector down seconds in the line"       "60" "$(jf det_down_s "$LINE")"
eq "segmenter up seconds in the line"        "60" "$(jf seg_up_s "$LINE")"
eq "sync down seconds in the line"           "60" "$(jf sync_down_s "$LINE")"
eq "legacy rec_down_s still emitted"         "0"  "$(jf rec_down_s "$LINE")"

# The camera-wedge KPI. Inverted on purpose: "down" means wedged, so the outage counter becomes the
# episode counter and the outage seconds become time-stuck. The 2026-08-26 wedge lasted 10h and left
# no durable record anywhere that it had even happened, which is why "do we need a smart plug?" had
# no number behind it.
acc_reset $TODAY 1000
acc_tick wedge 1 60 1060                     # healthy: not wedged
eq "a healthy camera banks no wedge time"    "0" "$ACC_wedge_DOWN"
acc_tick wedge 0 60 1120                     # classified wedged
acc_tick wedge 0 60 1180
eq "time spent wedged accrues"               "120" "$ACC_wedge_DOWN"
eq "an ongoing wedge is not an episode yet"  "0"   "$ACC_wedge_OUT"
acc_tick wedge 1 60 1240                     # power-cycled / recovered
eq "the closed wedge counts as one episode"  "1"   "$ACC_wedge_OUT"
eq "…with its duration as the worst"         "120" "$ACC_wedge_WORST"
LINE=$(daily_line $TODAY 1240 true)
eq "wedge episodes reach the daily line"     "1"   "$(jf wedge_episodes "$LINE")"
eq "wedge seconds reach the daily line"      "120" "$(jf wedge_s "$LINE")"

# A day boundary must not silently close an outage that is still open.
acc_reset $TODAY 1000
acc_tick sync 0 60 1180
acc_carry 20260827 2000
eq "an open outage carries into the new day" "2000" "$ACC_sync_SINCE"
eq "…with the new day's counters reset"      "0"    "$ACC_sync_DOWN"

# =============================================================================================
describe "seg_stalled — a live session that stops producing is dead (2026-09-25)"
# The incident: after the camera came back, a 2K run wrote one segment and then held the socket open
# for 17 min with nothing arriving. ffmpeg stayed alive, so the run loop never ended it, and the ring
# pruner deleted the one stuck segment. SEG_STALL_SECS=45 in production; segments land every <=~12s.
SAVED_RING="$RING_DIR"; RING_DIR="$SANDBOX/stallring"; mkdir -p "$RING_DIR"; SEG_STALL_SECS=45
st(){ if seg_stalled "$1" "$2"; then echo stalled; else echo live; fi; }
eq "a fresh run gets connect grace"               "live"    "$(st 1000 1030)"
touch -d @1090 "$RING_DIR/seg_a.mp4"
eq "a segment 10s ago = producing"                "live"    "$(st 1000 1100)"
eq "the newest segment 60s old = stalled"         "stalled" "$(st 1000 1150)"
rm -f "$RING_DIR"/seg_*.mp4
eq "no segment at all past the grace = stalled"   "stalled" "$(st 1000 1100)"
touch -d @500 "$RING_DIR/seg_old.mp4"
eq "a previous run's segment doesn't count"       "stalled" "$(st 1000 1100)"
eq "…but the grace still applies to a new run"    "live"    "$(st 1000 1020)"
RING_DIR="$SAVED_RING"

# =============================================================================================
describe "power_cycle_camera — the smart-plug lever, with guards that survive restarts"
mkdir -p "$(dirname "$LOG")"
REBOOT_STATE="$SANDBOX/.reboots"; REBOOT_EVERY_SECS=600; REBOOT_MAX_PER_DAY=2
CYCLES="$SANDBOX/cycles"; : > "$CYCLES"
FAKE_NOW=10000; FAKE_DAY=20260925
date(){ case "${1:-}" in +%s) echo "$FAKE_NOW";; +%Y%m%d) echo "$FAKE_DAY";; *) command date "$@";; esac; }
ncycles(){ grep -c . "$CYCLES"; }

CAM_POWER_CMD=""
power_cycle_camera 400
eq "no command configured = alert only"           "0" "$(ncycles)"

CAM_POWER_CMD="echo x >> '$CYCLES'"; rm -f "$REBOOT_STATE"
power_cycle_camera 400
eq "first wedge power-cycles"                     "1" "$(ncycles)"
FAKE_NOW=10300; power_cycle_camera 700
eq "not again inside REBOOT_EVERY_SECS"           "1" "$(ncycles)"
# THE regression guard: the gap must come from disk, not RAM. Restarting the process (fresh locals)
# is modeled by nothing at all here -- the function keeps no state of its own; only $REBOOT_STATE.
FAKE_NOW=10700; power_cycle_camera 1100
eq "after the gap it may cycle again"             "2" "$(ncycles)"
FAKE_NOW=11400; power_cycle_camera 1800
eq "the daily cap stops a power-cycling loop"     "2" "$(ncycles)"
FAKE_NOW=12100; power_cycle_camera 2500
eq "…and stays stopped for the day"               "2" "$(ncycles)"
FAKE_DAY=20260926; FAKE_NOW=90000; power_cycle_camera 400
eq "a new day restores the budget"                "3" "$(ncycles)"

CAM_POWER_CMD="exit 3"; FAKE_NOW=100000; FAKE_DAY=20260927
if power_cycle_camera 400; then r=ok; else r=fail; fi
eq "a failing plug command is reported"           "fail" "$r"
eq "…and still counts against the budget"         "1" "$(cut -d' ' -f2 "$REBOOT_STATE")"

# Wiring: only a WEDGED camera (pings, RTSP dead) is power-cycled; unreachable is power/network.
CAM_POWER_CMD="echo x >> '$CYCLES'"; rm -f "$REBOOT_STATE"; : > "$CYCLES"; FAKE_NOW=200000
cam_pings(){ return 1; }; reboot_camera 400
eq "an unreachable camera is not power-cycled"    "0" "$(ncycles)"
cam_pings(){ return 0; }; reboot_camera 400
eq "a wedged camera is power-cycled"              "1" "$(ncycles)"
unset -f date

# =============================================================================================
describe "telemetry paths — each camera owns its files (multi-camera step 1, 2026-09-26)"
# =============================================================================================
# The harness above exports explicit paths, so the DEFAULTS are checked by re-sourcing in a subshell
# with them unset. At CAMERAS_DIR root, two keepers would rewrite the same wifi/daily_health file.
paths=$(unset EVENTS_LOG WIFI_LOG DAILY_HEALTH CAM_LABEL
        OUT_DIR="$SANDBOX/Camaras/Camara2" RECORD_PREROLL_LIB=1 . "$SUT" >/dev/null 2>&1
        printf '%s|%s|%s|%s' "$EVENTS_LOG" "$WIFI_LOG" "$DAILY_HEALTH" "$CAM_LABEL")
IFS='|' read -r p_ev p_wifi p_daily p_label <<<"$paths"
eq "events.jsonl defaults into the camera folder"       "$SANDBOX/Camaras/Camara2/events.jsonl"       "$p_ev"
eq "wifi.jsonl defaults into the camera folder"         "$SANDBOX/Camaras/Camara2/wifi.jsonl"         "$p_wifi"
eq "daily_health.jsonl defaults into the camera folder" "$SANDBOX/Camaras/Camara2/daily_health.jsonl" "$p_daily"
eq "the camera id is the folder name"                   "Camara2"                                     "$p_label"
# The env override still wins: that is how a test camera is kept out of the app's view.
# (Plain assignments, not `VAR=x . file`: a prefix assignment to `.` is undone when the source returns.)
p_ev=$(EVENTS_LOG="$SANDBOX/events_cam2.jsonl"; OUT_DIR="$SANDBOX/Camaras/Camara2"
       RECORD_PREROLL_LIB=1 . "$SUT" >/dev/null 2>&1; printf '%s' "$EVENTS_LOG")
eq "an explicit EVENTS_LOG in the env still wins"       "$SANDBOX/events_cam2.jsonl"                  "$p_ev"

# =============================================================================================
describe "clip names carry the camera (multi-camera F1, 2026-09-26)"
# =============================================================================================
# Two cameras can start a clip in the same second; the app keys favourites, labels, its catalog and
# offline files by clip NAME, so the name itself must be unique across cameras.
eq "the clip name ends with the camera tag"   "mt_20260926_125715_out" "$(clip_base 20260926_125715)"
tag=$(unset CLIP_TAG; CAM_LABEL="Patio trasero/2"; OUT_DIR="$SANDBOX/out"
      RECORD_PREROLL_LIB=1 . "$SUT" >/dev/null 2>&1; printf '%s' "$CLIP_TAG")
eq "the tag is filename-safe"                 "Patio_trasero_2" "$tag"
# metrics.csv 'datetime' stays the bare timestamp even though the clip column now carries the tag.
export METRICS="$SANDBOX/metrics.csv"; rm -f "$METRICS"
printf '12.5' > "$SANDBOX/out/mt_20260926_125715_out.mp4"
write_metrics_row "$SANDBOX/out/mt_20260926_125715_out.mp4" 7.8 1.2 40 >/dev/null 2>&1
row=$(tail -1 "$METRICS")
eq "metrics row: clip = full name"            "mt_20260926_125715_out" "${row%%,*}"
eq "metrics row: datetime = timestamp only"   "20260926_125715" "$(printf '%s' "$row" | cut -d, -f2)"

# =============================================================================================
describe "enqueue_upload — every finalized clip joins the shared FIFO (multi-camera B1)"
# =============================================================================================
export UPLOAD_QUEUE="$SANDBOX/queue"; rm -rf "$UPLOAD_QUEUE"
enqueue_upload "$SANDBOX/out/2026/09/26/mt_20260926_125715_out.mp4"
enqueue_upload "$SANDBOX/out/2026/09/26/mt_20260926_125731_out.mp4"
markers=$(ls -1 "$UPLOAD_QUEUE" | grep -v '^\.')
eq "one marker per clip"                     "2" "$(printf '%s\n' "$markers" | grep -c .)"
eq "nothing half-written is left behind"     "0" "$(ls -1A "$UPLOAD_QUEUE" | grep -c '^\.tmp')"
first=$(printf '%s\n' "$markers" | sort | head -1)
eq "sorted by name = enqueue order"          "$SANDBOX/out/2026/09/26/mt_20260926_125715_out.mp4" "$(head -1 "$UPLOAD_QUEUE/$first")"
case "$first" in [0-9]*.out.mt_20260926_125715_out) ok "marker name = <ns>.<camera>.<clip>" ;;
                 *) no "marker name = <ns>.<camera>.<clip>" "<ns>.out.mt_20260926_125715_out" "$first" ;; esac

# =============================================================================================
printf '\n\033[1m%d passed, %d failed\033[0m\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
