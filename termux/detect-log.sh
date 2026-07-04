#!/data/data/com.termux/files/usr/bin/bash
# detect-log.sh — Detects motion and ONLY logs events (does NOT record video). Useful to compare
# two detectors (e.g. 2K vs 360p sub-stream) using a single RTSP connection each, without opening
# recording connections. Writes an events CSV: one event = sustained motion (merges triggers that
# are less than PAD seconds apart).
#
# Usage: detect-log.sh RTSP_URL CROP TAG [YAVG_TH] [FPS] [DIFF] [PAD] [TIMEOUT_us]
#   CROP in the stream's own coords (2K -> 760:1296:818:0 ; 360p -> 210:360:226:0)
#   TAG  label for the output files (e.g. 2k or 360)
# Output: ~/triggers_TAG.csv  and  ~/detlog_TAG.log
set -u
URL="${1:?usage: detect-log.sh RTSP_URL CROP TAG [YAVG_TH] [FPS] [DIFF] [PAD]}"
CROP="${2:?missing CROP W:H:X:Y}"
TAG="${3:?missing TAG}"
YTH="${4:-0.4}"; FPS="${5:-3}"; DIFF="${6:-20}"; PAD="${7:-5}"; TO="${8:-10000000}"
OUT="$HOME/triggers_$TAG.csv"; LOG="$HOME/detlog_$TAG.log"
log(){ echo "$(date '+%F %T') [$TAG] $*" | tee -a "$LOG"; }
[ -f "$OUT" ] || echo "event,start,end,dur_s,yavg_max,samples_over_threshold" > "$OUT"

EVT=0; active=0; start_ts=0; start_h=""; end_ts=0; mx=0; n=0
log "=== detect-log starts | $(echo "$URL"|sed 's#//[^@]*@#//...@#') roi=$CROP YAVG_TH=$YTH fps=$FPS ==="

while true; do
  ffmpeg -nostdin -loglevel info -rtsp_transport tcp -timeout "$TO" -i "$URL" -an \
    -vf "fps=$FPS,${CROP:+crop=$CROP,}tblend=all_mode=difference,format=gray,lut=y=if(gt(val\,$DIFF)\,255\,0),signalstats,metadata=print" \
    -f null - 2>&1 >/dev/null |
  grep --line-buffered -F 'signalstats.YAVG=' |
  while true; do
    if IFS= read -r -t 2 line; then
      val=${line##*YAVG=}; val=${val%% *}
      if awk "BEGIN{exit !(${val}+0 > $YTH)}" 2>/dev/null; then   # motion
        now=$(date +%s)
        if [ "$active" = 0 ]; then
          active=1; EVT=$((EVT+1)); start_ts=$now; start_h=$(date '+%F %T'); mx=$val; n=1
          log "►► EVENT $EVT starts (YAVG=$val)"
        else
          n=$((n+1)); awk "BEGIN{exit !(${val}+0 > ${mx}+0)}" 2>/dev/null && mx=$val
        fi
        end_ts=$now
      fi
    else
      # EOF (pipe closed = stream dropped) vs normal read timeout
      if [ "$?" -le 128 ]; then log "!! pipe closed (stream dropped)"; break; fi
    fi
    # close the event? (no motion for PAD s)
    if [ "$active" = 1 ]; then
      now=$(date +%s)
      if [ "$now" -ge "$((end_ts + PAD))" ]; then
        dur=$((end_ts - start_ts)); [ "$dur" -lt 1 ] && dur=1
        echo "$EVT,$start_h,$(date -d "@$end_ts" '+%T' 2>/dev/null || echo "$end_ts"),$dur,$mx,$n" >> "$OUT"
        log "■■ EVENT $EVT end (dur=${dur}s yavg_max=$mx samples=$n)"
        active=0
      fi
    fi
  done
  log "detector dropped (RTSP?); retry in 5s"; sleep 5
done
