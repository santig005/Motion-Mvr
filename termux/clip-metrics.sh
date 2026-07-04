#!/data/data/com.termux/files/usr/bin/bash
# clip-metrics.sh — Emits a CSV with per-clip metrics to compare detectors WITHOUT watching every
# video. It recomputes YAVG (the same signal the detector uses) over each file and summarizes:
# duration, size, YAVG max/mean and number of frames with motion.
#
# Usage:  ./clip-metrics.sh DIR CROP [YAVG_TH] [DET_FPS] [DIFF_TH]
#   DIR   folder with the .mp4 files
#   CROP  ROI in the video's own coords, "W:H:X:Y" (2K -> 760:1296:818:0 ; 360p -> 210:360:226:0)
# e.g.  ./clip-metrics.sh ~/recordings 760:1296:818:0
#       ./clip-metrics.sh /sdcard/Movies/cam1 210:360:226:0
set -u
DIR="${1:?usage: clip-metrics.sh DIR CROP [YAVG_TH] [FPS] [DIFF]}"
CROP="${2:?missing CROP W:H:X:Y}"
YTH="${3:-0.4}"; FPS="${4:-3}"; DIFF="${5:-20}"

echo "clip,datetime,dur_s,size_kb,yavg_max,yavg_mean,motion_frames"
for f in "$DIR"/*.mp4; do
  [ -f "$f" ] || continue
  dur=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$f" 2>/dev/null)
  sz=$(( $(stat -c %s "$f" 2>/dev/null) / 1024 ))
  vals=$(ffmpeg -nostdin -loglevel info -i "$f" -an \
      -vf "fps=$FPS,crop=$CROP,tblend=all_mode=difference,format=gray,lut=y=if(gt(val\,$DIFF)\,255\,0),signalstats,metadata=print" \
      -f null - 2>&1 | grep -F "signalstats.YAVG=" | sed "s/.*YAVG=//; s/ .*//")
  read -r mx mean n < <(echo "$vals" | awk -v th="$YTH" '{s+=$1; if($1>m)m=$1; if($1>th)c++} END{printf "%.3f %.3f %d", m+0, (NR?s/NR:0), c+0}')
  base=$(basename "$f" .mp4); dt=${base#mt_}
  echo "$base,$dt,${dur:-0},$sz,$mx,$mean,$n"
done
