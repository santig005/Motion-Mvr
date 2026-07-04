#!/data/data/com.termux/files/usr/bin/bash
# latency-report.sh — One-shot diagnostic: real end-to-end latency of the pipeline
#   motion detected -> clip built locally -> uploaded to Google Drive
# for the most recent clips, using data that's ALREADY on disk (metrics.csv, cloud-sync's log,
# the clip's own mtime). Adds no new recording path, no wakeups, no persistent process: run it
# manually (e.g. over SSH) whenever you want a read on real timings.
#
# Requires cloud-sync.sh's clip upload to run with rclone's -v flag (see cloud-sync.sh), which
# leaves one "Copied (new)" line per uploaded file with rclone's own timestamp.
#
# "Visible in the app" beyond the upload itself is bounded by how the app learns about it: near-
# instant on manual pull-to-refresh (Drive's files.list has no meaningful propagation delay), or up
# to the ~15min NewClipsWorker poll interval if relying on notifications. This script measures the
# pipeline latency that both paths share, which is the real floor for any notification strategy.
#
# Usage: CAM_ENV=~/cam1.env ./latency-report.sh [N]     # N = how many recent clips to check (default 30)
set -u
ENV_FILE="${CAM_ENV:-$HOME/cam.env}"
[ -f "$ENV_FILE" ] && . "$ENV_FILE"
[ -f "$HOME/cloud.env" ] && . "$HOME/cloud.env"
OUT_DIR="${OUT_DIR:-$HOME/recordings}"
PREROLL="${PREROLL:-3}"
METRICS="${METRICS:-$OUT_DIR/metrics.csv}"
CLOUD_LOG="${SYNC_LOG:-$HOME/logs/cloud-sync.log}"
N="${1:-30}"

[ -f "$METRICS" ] || { echo "no metrics.csv at $METRICS" >&2; exit 1; }
[ -f "$CLOUD_LOG" ] || echo "!! warning: no cloud-sync log at $CLOUD_LOG (upload_s will be NA)" >&2

tmp_totals=$(mktemp)
printf "%-24s %8s %8s %8s %8s\n" "clip" "build_s" "upload_s" "total_s" "dur_s"
tail -n +2 "$METRICS" | tail -n "$N" | while IFS=, read -r clip dt dur _rest; do
  [ -z "$clip" ] && continue
  clip_start=$(date -d "${dt:0:4}-${dt:4:2}-${dt:6:2} ${dt:9:2}:${dt:11:2}:${dt:13:2}" +%s 2>/dev/null) || continue
  motion_epoch=$((clip_start + PREROLL))
  datedir=$(date -d "@$clip_start" "+%Y/%m/%d" 2>/dev/null)
  f="$OUT_DIR/$datedir/$clip.mp4"
  ready_epoch=""
  [ -f "$f" ] && ready_epoch=$(stat -c %Y "$f" 2>/dev/null)

  upload_epoch=""
  upload_line=$(grep -F "$clip.mp4" "$CLOUD_LOG" 2>/dev/null | grep -F "Copied" | tail -n1)
  if [ -n "$upload_line" ]; then
    ts=$(echo "$upload_line" | awk '{print $1" "$2}')
    upload_epoch=$(date -d "$ts" +%s 2>/dev/null)
    [ -z "$upload_epoch" ] && upload_epoch=$(date -d "$(echo "$ts" | sed 's#/#-#g')" +%s 2>/dev/null)
  fi

  build_s="NA"; upload_s="NA"; total_s="NA"
  [ -n "$ready_epoch" ] && build_s=$((ready_epoch - motion_epoch))
  if [ -n "$ready_epoch" ] && [ -n "$upload_epoch" ]; then
    upload_s=$((upload_epoch - ready_epoch))
    total_s=$((upload_epoch - motion_epoch))
    echo "$total_s" >> "$tmp_totals"
  fi
  printf "%-24s %8s %8s %8s %8s\n" "$clip" "$build_s" "$upload_s" "$total_s" "$dur"
done

if [ -s "$tmp_totals" ]; then
  echo "---"
  sort -n "$tmp_totals" -o "$tmp_totals"
  awk '{ a[NR]=$1; sum+=$1; n++ }
       END {
         avg=sum/n; med=a[int((n+1)/2)]; p90=a[(int(n*0.9)<1)?1:int(n*0.9)]
         printf "motion -> uploaded to Drive, n=%d clips: avg=%.0fs median=%ds p90=%ds max=%ds min=%ds\n", \
                n, avg, med, p90, a[n], a[1]
       }' "$tmp_totals"
else
  echo "--- no complete (motion -> uploaded) samples yet: check that cloud-sync.sh ran with -v and clips are recent enough to still be local ---"
fi
rm -f "$tmp_totals"
