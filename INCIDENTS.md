# Incidents

A log of the real failures of this system, written after the fact. It exists because the useful part
of an outage is not that it happened but what it taught, and that part decays fast: within weeks the
fix looks obvious and the four wrong theories that preceded it are gone.

Every entry follows the same shape:

**symptom → hypotheses ruled out → root cause → fix (with commit) → what it changed about the design**

The *hypotheses ruled out* section is not padding. Several of these incidents were diagnosed wrongly
first — twice by confident argument that measurement then destroyed — and two of the entries below
(2026-07-18 and 2026-07-27, joined later by part of 2026-08-16) are worth more than the rest precisely
because the alarm was wrong, or the obvious fix was investigated and rejected.

Commit SHAs refer to this repository. Where a change was deployed to the phone but is not yet
committed, that is stated.

## Summary

| # | Date | Incident | Impact | Root cause | Fix |
|---|---|---|---|---|---|
| 1 | 2026-07-15 | Slow charge + RTSP reconnect storm | ~45 mA net charge; 124 reconnects per 2000 log lines | Weak charger *and*, separately, a hung camera RTSP; runs outlived the health bar so fallback never engaged | `5821101`, `c0cfbeb`, `54e4340` |
| 2 | 2026-07-16 | Upload lag = Drive 403 quota cascade | Clips 12–17 min late; 230 files / 210 MiB needlessly re-uploaded | Full-tree LIST every 25 s tripped Drive's per-minute quota; a 403 on LIST makes rclone re-upload | `92a5695`, `d904056`, `137b391` |
| 3 | 2026-07-18 | "5 h without reporting" — **false alarm** | None. The NVR was healthy all day | App never re-fetched on resume; morning state judged against the current clock | `f6c56bd` |
| 4 | 2026-07-20 | Clips split in two | ~23 false splits/day | Gap test used start-to-start spacing against a SEG_TIME that does not match the real ~12 s GOP | `9a8b916` |
| 5 | 2026-07-27 | Camera wedged 12 h | ~12 h barely recording | Camera's own RTSP subsystem hung; nothing phone-side can fix it. **HTTP nudge tested and discarded** | `5de12b7`, `5af9a78` |
| 6 | 2026-08-01 | 21 h outage | 21 h 10 m not recorded, 10 h 47 m of it with the phone powered on | Phone unplugged (trivial) + Android restricted Termux, so Termux:Boot never ran (systemic) | `ab5a750` |
| 7 | 2026-08-12 | Recording but blind | 4 h 20 m of clean 2K, 0 clips | Detector health scored by run duration; every failure cleared the bar | `26854fe`, `63d2ef8` |
| 8 | 2026-08-14 | "Drive full" | Uploads stopped ~08:30; 4.8 GiB of phantom usage | Retention deleted to the Drive **trash**, which still counts against quota | uncommitted (working tree) |
| 9 | 2026-08-16 | Three separate outages in one day | 13 h 40 m blind; 18 zero-second clips since July; 20 min at a dead IP | (a) camera globally degraded + watchdog blind to the detector, (b) truncated segment read as duration 0, (c) camera moved by DHCP | `c4ebe40`, `0b6b112`, `063c1d9` |

---

## 1. 2026-07-15 — Slow charge and the RTSP reconnect storm

**Symptom.** The NVR phone (Galaxy A03, SM-A035M) was plugged into AC and charging almost not at all.
Separately and on the same day, both ffmpeg processes — the 2K recorder on ch0 and the 360p detector
on ch1 — were dropping and reconnecting every 10–18 s with `Invalid data found when processing input`:
124 connects and 123 drops in the last 2000 log lines. The app fired a "camera no signal" alert at
~13:33.

**Hypotheses ruled out.**

| Hypothesis | How it died |
|---|---|
| Battery wear | `HEALTH=GOOD`, 24.9 °C. Swapping the charger took the current from ~45 mA to ~900 mA (867–945 mA) immediately. |
| Weak Wi-Fi causes the storm | Wi-Fi improved on its own — RSSI −70 dBm / 19 Mbps → −45 dBm / 58 Mbps, ping to the camera 9–304 ms → 2.7–6.9 ms at 0 % loss — and the storm continued unchanged. Demoted to co-factor. |
| CPU exhaustion | Load average ~5.1 on 8 cores, but CPU ~100 % idle in snapshots. It was I/O, process-spawn churn and zram pressure (~1.4 GB swap used, ~90 MB free RAM), not compute. |
| The vendor app competing for the camera | AJCloud/FAMVIVA was not running: 0 % CPU. |

**Root cause.** Two independent faults sharing a symptom day.

*Slow charge:* a weak charger, cable or port. Net charge current measured ~45 mA across 10 samples
(−9 mA discharging to +64 mA) against a healthy 1000–2000 mA — the NVR's continuous draw was
approximately what the charger supplied, so 46 % → 100 % would have taken about 60 h. The low battery
temperature was the tell: a real charge warms the pack.

*The storm:* the camera's own RTSP/encoder had hung. Direct `ffprobe` of **both** channels failed
identically while ping stayed clean. A power-cycle cleared it — five fresh 2K segments in two minutes,
storm gone.

And underneath, a design defect that made the storm unbounded: each failed run lasted ~10–15 s, above
`HEALTHY_SECS=8`, so the failure counter reset every single time and the sub-stream fallback never
engaged. 624 drops in one afternoon, 0 fallbacks. Every log line read `failure 0`.

**Fix.**
- `5821101` — a rolling-window flap guard: count 2K drops whose run was shorter than
  `SUSTAINED_2K_SECS` (90 s = genuinely stable) within `FLAP_WINDOW_SECS` (180 s); `FLAP_MAX_DROPS` (4)
  of them fall back to the sub-stream. A sustained run clears the history, so isolated blips are
  tolerated. The original short-run and probe paths are untouched.
- `c0cfbeb` — the segmenter publishes `rec_mode` and `rec_2k_drops_1h` into `status.json`, pushed
  immediately on a 2K↔SUB change.
- `54e4340` — the app surfaces both as banners ("recording in 360p", "2K unstable — N drops/h").

**What it changed about the design.** This is where the project first learned that *a run that lasted
long enough is not the same as a run that worked*. The lesson had to be re-learned twice more
(`d904056` for the segmenter's `produced` check, `26854fe` for the detector's frame check) before it
generalised. It also established a rule about automatic degradation: the moment the NVR could silently
drop to 360p to protect itself, the app had to be able to say so, or a permanently degraded system
would look identical to a healthy one.

---

## 2. 2026-07-16 — Upload lag: a Drive 403 quota cascade

**Symptom.** Clips arriving on Drive 12–17 minutes late in a morning window (peak 08:04–08:16 local),
against a normal end-to-end latency of a couple of minutes.

**Hypotheses ruled out.**

| Hypothesis | How it died |
|---|---|
| Upstream bandwidth | The volume being sent was the problem, not the pipe — most of it was files that were already there. |
| The camera is chronically broken ("2K unstable") | A separate strand of the same investigation: 2K verified recording 2304×1296 in one uninterrupted 3 h+ ffmpeg run the same evening. |
| Camera RTSP session limit | Decisive experiment: the camera accepted **5 concurrent sessions** (2×ch0 + 3×ch1) with zero eviction and no harm to existing streams. |
| A slow live-view consumer blocking the camera's per-channel frame queue | Tested by SIGSTOPping a second ch1 session for 100 s. Null result — the camera isolates a slow client. Theory disproven. |
| Give rclone a personal `client_id` | Rejected on product grounds, not technical ones: the system must stay replicable with zero per-user setup. |

**Root cause.** rclone's shared default `client_id` was hitting Drive's per-minute query quota — 64
× HTTP 403 that day. The damaging part is what rclone does with a 403 on a *destination LIST*: it
treats the directory as missing and re-uploads it. One cascade re-sent 230 files / 210 MiB of the
previous day, and fresh clips queued behind that with `--transfers 3`. The trigger was the sync loop
re-listing every dated directory of the whole tree every 25 s.

**Fix.** `92a5695` split the sync into three lanes:

| Lane | Cadence | Scope |
|---|---|---|
| fast | 25 s | only each camera's **today** folder (+ yesterday's for the first 30 min after midnight) — one LIST per camera per cycle |
| self-heal | `HEAL_EVERY` = 1 h | the only full-tree pass, with `--fast-list` (one recursive call) |
| retention | 8 h | piggybacks on a heal pass, so the local purge stays gated on a just-confirmed full upload |

Two more fixes shipped the same day out of the same investigation: `d904056` (publish `REC_STATE`
mid-run, and count only runs that actually wrote a segment as healthy) and `137b391` (the app releases
its live RTSP session on `ON_STOP` instead of leaving a zombie stream running in the background).
Documented in `6b7ef42`.

**What it changed about the design.** The sync design stopped being about throughput and became about
the **blast radius of a single failure**: cut LIST queries roughly tenfold and confine a 403's damage
to one day's directory. Second, the stale-`REC_STATE` bug found alongside it — state written only when
a run *ends*, so a stable run never updates it and the app sat on "SUB" for hours — is the same family
as the 2026-07-18 false alarm below: showing old state as if it were current. That family of bug turned
out to be the most common source of the user reporting something that was not happening.

---

## 3. 2026-07-18 — "5 hours without reporting": the alarm was wrong

The most instructive entry here, because nothing was broken in the system the alarm was about.

**Symptom.** The app displayed a banner saying the camera had not reported for about 5 hours.

**Hypotheses ruled out.** All of them NVR-side, all checked over SSH before the app was suspected:

| Hypothesis | How it died |
|---|---|
| A recording outage | Exactly one gap all day: **47 s** (11:13:01 → 11:13:48 local), a segmenter RTSP stall that auto-recovered. |
| Uploads stalled | Clips, `metrics.csv` and the heartbeat all uploaded normally. Two 403 bursts (10:47, 14:17) both retried successfully. |
| The phone or a process died | Uptime 6 d, battery 89 % charging, 37 GB free, tmux sessions alive since Jul 16. |
| The gaps visible in Drive's revision history prove missing uploads | **A trap in the instrument, not a real finding.** Drive keeps roughly 100 revisions per file and *thins* the old ones: `status.json` showed 19 days of history with 4–6 h holes in the old part and 20-minute density only in the last few hours. A hole in old revisions is not evidence of anything. Control: compare against a high-frequency file (`metrics.csv`), whose 100-revision cap truncates by age without visible thinning. |

**Root cause.** An app-side stale-state bug. `AppNav.kt:344` read
`LaunchedEffect(Unit) { if (!vm.loadedOnce) vm.load() }` — reopening the app from background never
re-queried anything. It rendered the `cameraHealth` object it had fetched that morning, while
`Models.kt:235` computed the "X hours ago" label against the *current* clock. The banner was
arithmetically correct and factually false. A second path made it stickier: `MainViewModel.load()`
silently kept the previous `cameraHealth` if `listClips()` threw.

The corroborating evidence that the data was actually fresh was already in the system:
`NewClipsWorker`, polling every 15 minutes, never fired the staleness push notification, and clips had
been landing on Drive within 1–2 minutes throughout the window.

**Fix.** `f6c56bd`:
- reopening from background re-fetches whenever the in-memory data is older than 60 s;
- an explicit freshness line ("Actualizado HH:MM" / "Datos sin refrescar");
- the not-reporting banner only fires on **freshly fetched** data with a genuinely stale heartbeat.

The same commit added the Salud screen — an outage timeline built from the NVR's `events.jsonl` (down/up
pairs with durations) plus sync-pipeline health from `sync_status.json`, both degrading gracefully when
absent. `events.jsonl` and `sync_status.json` themselves came from `b78cbec` the same day.

**What it changed about the design.** Two rules, one about the product and one about the method.

*Product:* a health display must be able to distinguish **"the NVR is silent"** from **"this app has
not refreshed"**. These are opposite conditions and they had been rendering identically. The order of
work mattered: the false-positive fix had to ship *before* the monitoring dashboard, because a
dashboard built on the same state would have told the same lie with more authority.

*Method:* the forensic tools have failure modes of their own. Almost half of the investigation went
into a Drive revision-history artefact that looked exactly like the evidence being sought. Validate the
instrument before trusting the measurement — a habit that paid off again on 2026-08-14 (a device that
answers ping is not necessarily the device you think) and 2026-08-16 (a stubbed `tmux` hiding the
effect under test).

---

## 4. 2026-07-20 — Clips split in two

**Symptom.** Single motion events arriving as two clips: a stray 1–2 s pre/post-roll fragment (often
`frames=0`) plus the real clip a few seconds later. The clearest example — a woman mopping —
came out as `mt_150443` (2 s) and `mt_150446` (20.5 s), seamless across 15:04:46, verified frame by
frame.

**Hypotheses ruled out.**
- *A genuine recording dropout.* The frames are contiguous across the boundary, and the three largest
  suspected gaps (19 / 25 / 27 s at 14:08 and 14:43) had no segmenter drop logged at all.
- *Motion detection re-triggering.* The split happens in clip assembly, downstream of detection.

**Root cause.** `build_clip`'s gap-split used `gap_th = 2*SEG_TIME+4` (= 12 s) and compared the
**start-to-start** delta of consecutive ring segments. But with `-c:v copy -f segment`, ffmpeg can only
cut at keyframes, so a real segment lasts approximately the camera's GOP — measured at **12.0–12.1 s**
in the live ring — and not `SEG_TIME` (4 s). Since strftime names round to whole seconds, healthy
consecutive starts sit 12–16 s apart and tripped a 12 s threshold. Result: 26 gap-splits per day, of
which roughly 23 were false.

**Fix.** `9a8b916` — compare the actual hole, `next.start − prev.end` (both arrays were already
available at that point in the code), and split only when it exceeds `SEG_TIME+2` (6 s). Contiguous
segments abut within under a second regardless of how long they ran, so the rule is immune to GOP
jitter while preserving the original orphan/dropout-split intent. Unit-tested. It fixes future clips
only; the clips already split on 2026-07-20 stay split on Drive.

**What it changed about the design.** The configured value described what was *requested*, not what
the hardware *does*: `SEG_TIME=4` is a request to ffmpeg, while ~12 s is what the camera's keyframe
interval permits. Every rule derived arithmetically from the requested value was wrong, and would have
stayed wrong on any camera with a different GOP. Derive thresholds from the measured artefact, not from
the setting. The same investigation surfaced that the deployed configuration had drifted from
`cam.env.example` — `PREROLL=4`, `POSTROLL=12` against 3/3 documented — which is its own small lesson
about trusting the example file as documentation of production.

---

## 5. 2026-07-27 — Camera wedged 12 h, and the obvious fix that was discarded

The second entry worth keeping in full, because the valuable part is a fix that was built, tested
against the live fault, and thrown away.

**Symptom.** Recording stopped at ~11:12 (last clip `mt_20260727_111121`) and the user noticed at
midday. Roughly 12 hours with almost nothing recorded.

**Hypotheses ruled out.**

| Hypothesis | How it died |
|---|---|
| The NVR or the phone | Uptime 6.7 d, three tmux sessions alive, uploads current. |
| RF / Wi-Fi | Ping to the camera clean at 3 ms. |
| The 2026-07-16 slow-consumer theory | That failure was ch1-only; this one rejected **both** channels. |
| `down_since` is buggy | It was not — it correctly reflected the last transition. There genuinely were brief recoveries (a 188 s SUB run at 20:40, blips at 20:37 and 23:30) that relapsed within minutes. |
| **HTTP nudge** | See below. |

**The HTTP nudge, in detail.** The camera answered ping and its port 80 was alive — `/tmpfs/auto.jpg`
and `/tmpfs/snap.jpg` both returned 200 — so the cheap fix looked obvious: find an HTTP or ONVIF reboot
endpoint and have the watchdog call it. Every candidate was tried against the live wedge:
`/onvif/*`, `/cgi-bin/hi3510/*`, `/reboot`, `/system.cgi?...`, `/hy-cgi/*` — all 404. Hammering the
snapshot endpoint five times plus the reboot candidates and waiting 45 s did **not** unstick RTSP. The
23:30 recovery that followed was coincidence, not the probing; it relapsed like the others.

The conclusion was recorded as a fact about this hardware: **when this camera's RTSP wedges there is
no HTTP lever.** Only a real reboot clears it, and reboot on an AJCloud/Wansview device travels over
the vendor's proprietary P2P protocol (PPPP/CS2 over UDP), not over anything web-shaped. That closes
off an entire class of "just have the watchdog poke it" designs, which is exactly the kind of thing
that is expensive to rediscover.

**Root cause.** The camera's own RTSP subsystem hangs (`Server: AJCloud Httpd Server 1.0`). ffmpeg
fails at *connect* with `Invalid data found` / `Error opening input` on both ch0 and ch1 while the
device is otherwise responsive. The recovery gap: `watchdog.sh` restarts phone-side software only. It
kicked ffmpeg once at 20:44, which was useless — the fault was in the camera, and the watchdog had no
lever on it. That gap is what made a camera fault into a twelve-hour outage.

**Fix.** `5de12b7` — `segmenter_loop` tracks continuous `produced=0` time. After `WEDGE_AFTER_SECS`
(360 s) with the camera **still answering ping**, it logs `camera WEDGED — REBOOT REQUIRED` and
records a `recording wedged` event; if ping also fails it reports *unreachable* instead (power or
network, not a wedge). Escalation is rate-limited by `REBOOT_EVERY_SECS` (600 s), and a single produced
segment resets the counter. `reboot_camera()` is a deliberately marked TODO — the hook where a smart
plug or a replayed P2P command gets plugged in. Companion `5af9a78`: the app notifies when a camera
that was down starts recording again, because the operator's job is now "reboot it when told", and that
loop needs a closing signal.

*Reverse-engineering follow-up, for the record.* The P2P reboot was pursued far enough to be costed
honestly. Discovery works (`f1 30` LanSearch → `f1 41` carrying DID `SPTG1JA8NRRPN9ZF`), but the camera
uses a non-standard handshake variant (`f1 45`/`f1 46` where `aiopppp` expects `f1 42`) and moves
commands onto a **TCP** channel on port 13723 rather than the UDP path the public libraries assume.
Framing is `[len16 BE][54 00][token/seq][encrypted payload]`; the reboot packet is identified (88 bytes)
and keepalives are the 12-byte ones. The encryption is neither `aiopppp`'s `xor1` nor `cam-reverse`'s
`XqBytes` — both were tried and neither decodes — and direct replay fails on per-session tokens.
Verdict: achievable, but a dedicated multi-session RE project. A smart plug on the existing hook is the
cheap and reliable close.

**What it changed about the design.** It introduced the classification of a fault by **who can fix
it**, which is now load-bearing across the whole system:

| Class | Signal | Correct response |
|---|---|---|
| Our software is broken | tmux session or ffmpeg gone | watchdog restarts it |
| Camera wedged | pings, RTSP returns `Invalid data`, `produced=0` | only a power-cycle helps → tell the human |
| Camera unreachable | no ping | power or network; a local restart is actively wrong |

That third row is not decoration. On 2026-08-16 it stopped the watchdog from restarting the recorder
six cycles in a row while the camera was unpingable at an address it no longer held.

---

## 6. 2026-08-01 — A 21-hour outage that was not the battery

**Symptom.** Nothing recorded from 08-01 11:53 to 08-02 09:03 — about **21 h 10 m**.

**Timeline**, reconstructed from logs:

| Time | Event |
|---|---|
| 08-01 11:53:17 | last clip `mt_20260801_115050` |
| 08-01 12:02:29 | `!! segmenter dropped (ran 59532s, produced=0)`; `camara1.motion.log` and `watchdog.log` go silent from here — cam1 **and the watchdog** died at this point |
| 08-01 17:01:44 | last heartbeat in `cloud-sync.log` → this is when the phone actually powered off, not midday |
| 08-01 ~22:16 | Android boots (confirmed by a 10:47 uptime measured at 09:03) — but Termux does not |
| 08-02 09:02:51 | `~/boot-start-cam.sh` run by hand; 09:03:05 `✅ recording` |

**10 h 47 m of the gap had the phone powered on and the NVR dead.**

**Hypotheses ruled out.** Two of the three initial conclusions were wrong, and the user corrected them:

| Hypothesis | How it died |
|---|---|
| The charger regressed (the July fault returning) | **Wrong.** The bad charger had already been replaced. The phone was simply left unplugged and ran down — a trivial cause, not a systemic one. Confirmed after the fact: 70 → 78 % in about 20 min once plugged back in. |
| The battery alert never fired | **Wrong.** The notifications did arrive; they were not acted on in time. On 08-01 the battery alerting chain worked correctly end to end. |
| `BOOT_COMPLETED` does not fire until the first screen unlock | Ruled out by a controlled physical reboot on 08-02, performed **without unlocking the screen at any point**: power-on 10:28:01 → SSH answering 21 s later → watchdog 10:29:35 → `✅ recording` 10:29:45. About **104 s from cold boot to recording**. |

**Root cause.** The one real, systemic failure: **Android had the Termux apps restricted** ("sleeping
apps" / battery optimisation) on the Samsung A03, so Termux:Boot never ran at the 22:16 boot. That is
precisely manual step #2 that `termux/boot-start-cam.sh` warns about. The user set them to
unrestricted on 08-02.

Two supporting observations. The staggered death — cam1 and the watchdog at 12:02, cloud-sync five
hours later — is consistent with Android's low-memory killer taking the heaviest process (the 2K
ffmpeg) first as the battery went critical; the absence of any drop in `events.jsonl` says SIGKILL,
with no chance to log. And the component whose death mattered was the **watchdog**: losing it removed
every possibility of auto-recovery.

No data was lost: all 73 clips of 08-01 are intact on Drive (73 local mp4 == 73 in
`gdrive:Camaras/Camara1/2026/08/01/`). What was lost is 21 hours that were never recorded.

**A second fault surfaced by the same restriction.** After the restart, `termux-battery-status` hung
for 20–25 s. `read_battery()` (`record-preroll.sh:158`, `timeout 8 ... || return 1`) responded by
**silently omitting the field**, so `status.json` carried no `battery`/`charging` at all and the app
simply showed no indicator — indistinguishable from a UI that had nothing to say.
`/sys/class/power_supply/` is not readable without root, so termux-api is the only battery path there
is.

**Fix.** `ab5a750` — `write_status` emits `"battery_unknown":true` when `read_battery` fails (logging
the transition exactly once); the app parses it into `CameraHealth.batteryUnknown` and shows a banner
ranked last. Deployed to the phone on 08-02 at 10:17. The same session paid off the git debt: three
changes that had been running in production for days were finally committed — `5de12b7` (wedge
classifier, live since 07-27), `5af9a78` (recovery notification) and `ab5a750`.

**What it changed about the design.** A sensor that fails by omitting its field is undetectable
downstream, because absence renders identically to "nothing to report". Failures must be published as
a **value** (`battery_unknown`), not as silence — the same principle that later forced unreadable ring
segments to be explicit rather than zero-length (2026-08-16). Second: the watchdog is not exempt from
the failure it guards against. It died alongside what it was meant to revive, which is why verified
boot-time auto-start matters more than in-process supervision, and why the 08-02 controlled reboot
(104 s, screen never unlocked) was worth doing deliberately rather than assuming.

**Operational trap worth keeping.** Three round-trips were lost because the user's shell was sitting
at a `>` continuation prompt from an unclosed single quote (`tmux new-session -d -s watch 'cd ~`).
Everything typed after that was swallowed without executing — "I ran it and nothing happened". When
that phrase appears, ask for a screenshot; the prompt gives it away instantly.

---

## 7. 2026-08-12 — Recording, but blind

**Symptom.** "Nothing has been recorded since midday" — while the app showed a stable 2K stream and
`status.json` reported `ok:true`, `recording_ok:true`.

It was two different windows, not one:

| Window | What was happening |
|---|---|
| 11:05 – 17:37 | Camera RTSP wedged — the 2026-07-27 pattern exactly: answers ping, both channels return `Invalid data`. |
| 17:37 – 21:55 | **The camera came back.** The segmenter recorded 2K without interruption for 4 h 20 m — one single run of 15 445 s, `produced=1` — while the *detector* never got back onto ch1: **978 reconnects, one single "detector connected" line, 0 motion events, 0 clips.** The ring filled with perfect footage and was pruned on schedule. |
| 21:55 → | Wedged again. |

**Hypotheses ruled out.**
- *A quiet afternoon.* Entirely plausible from outside, and precisely why nothing alerted — 0 clips is
  what a calm day looks like. Disproven by 978 reconnect attempts in the detector log.
- *A recording failure.* `recording_ok` was `true` and correctly so. The ring was fresh the whole time.

**Root cause.** Detector health was scored as `det_ran >= HEALTHY_SECS` (8 s). An ffmpeg that hangs at
RTSP open dies on the ~10 s timeout — which *clears* that bar. So every failure scored as healthy:
`dfails` never grew, the backoff never engaged, and the "can't connect" warning never fired. The log
read `failure 0` 978 times in a row.

This is the identical bug the segmenter had already fixed on 2026-07-16 with its `produced` check
(`d904056`). The detector never received the same treatment.

Two things compounded it. `recording_ok` only looks at whether fresh segments exist in the ring, so
"recording but not detecting" was structurally indistinguishable from "nothing moved". And
`reboot_camera()` shouted `REBOOT REQUIRED` **68 times that day** into a local log file with no route
to the app.

**Fix.** `26854fe`:
- health = *did the run deliver frames?* — reported out of the pipeline subshell via exit status (0/3),
  since `saw` cannot propagate out of a pipeline;
- `DET_STATE` → `detector_ok` / `detector_down_since` in `status.json`, pushed on transition rather
  than waiting for the 20-minute heartbeat;
- `WEDGE_STATE` → `camera_wedged` / `wedged_since`, cleared mid-run as well as post-run (a healthy run
  never exits, so a post-run-only clear would leave the flag stuck for hours after recovery);
- runs that never delivered a byte no longer count as 2K "flaps", so `rec_2k_drops_1h` stops describing
  a camera that was serving nothing;
- once classified wedged, retry at a slow steady cadence instead of letting the 2K↔SUB fallback reset
  `sfails` and hammer a dead camera every 5 s.

App side: `63d2ef8` adds `CameraHealth.detectorOk` / `cameraWedged` / `blindWhileRecording`.

**What it changed about the design.** The health model gained a **second axis**. "Are we recording?"
and "are we seeing?" are different questions, answered by different sensors, and the answer to the
first says nothing about the second: the ring can fill 24/7 while nothing is ever promoted to a clip.
Second: a diagnosis that exists only in a local log does not exist. Anything the NVR concludes must
have a route into `status.json`, or it is a message to no one — 68 times over.

One hypothesis was left open here: that after a wedge the camera serves only one RTSP session, so the
detector and the segmenter starve each other and whoever connects first wins. It was tested and
discarded four days later.

---

## 8. 2026-08-14 — "Drive is full"

**Symptom.** "Nothing recorded since 8:30." Four independent failures on the same day, and the one the
user can see is not a recording failure at all.

**Root cause of the visible failure.** 14.001 GiB of 15 GiB used, **58 KiB free**, with **4.813 GiB
sitting in the Drive trash and still counting against quota**. `cloud-sync.sh:191,193` called
`rclone delete` without `--drive-use-trash=false`, and the Drive backend trashes by default. The
30-day retention sweep had been running correctly, on schedule, and freeing **zero bytes** — for as
long as it had existed. Last clip uploaded: `mt_20260814_082625`, i.e. "8:30". 56 clips in Drive
against 79 local. The trash was verified to contain only files from `Camaras`, so emptying it was safe;
without the trash, usage was ~9.1 GiB of 15.

**Hypotheses ruled out — the network blackout.** A separate 3 h 23 m failure ran the same morning: the
router was restarted by hand at ~09:25, internet was back at ~09:32, and the phone did not recover
network until **12:55**. The signature in `cloud-sync.log` is continuous `no such host` — dead DNS, not
"network unreachable" — with not a single success in between; ping to the camera's raw IP also failed.
The phone had not rebooted (uptime 8 d).

| Hypothesis | How it died |
|---|---|
| Samsung Doze / App Standby (only active while unplugged, and the phone was on battery in that window) | **Retracted.** An app exempt from battery optimisation keeps network access in Doze; Doze has maintenance windows and there were *zero* successes in 3.5 h; and Doze exits on movement or plug-in. Termux was already unrestricted on that phone. |
| A Wi-Fi repeater with a second BSSID | Ruled out: `MARTIN_ELIECER` has a single BSSID, same OUI as the router's 5 GHz radio → direct connection. |
| "Move the phone to 5 GHz" (recommendation D1) | Impossible: the SM-A035M is 802.11 b/g/n, **2.4 GHz only**. The recommendation was invalid, and this is also why "internet came back at 9:32" was true on 5 GHz while the NVR stayed dark on 2.4. |
| The camera has wedged again (`.2` pings but gives no RTSP) | **No.** See the DHCP trap below — a real wedge *accepts* the connection and returns `Invalid data`; this was `Connection refused` with zero open ports, neither 80 nor 554. |

Airtime contention was real and measured — channel 9 (2452 MHz) shared with a `Pixel 9a` AP at −47 dBm,
**21 dB (~125×) stronger** than the router at −68 dBm; ping to the router at one hop showing 5 % loss
and 3.5 / 59.6 / 219 ms; drops per hour going 0.2 before the router restart → 10.9 after. But the
actual cause of the 3 h 23 m of *zero* successes was found by reproducing it twice the same day: **the
phone does not re-associate to Wi-Fi when the AP restarts, with the screen off, while unplugged.** When
the router channel was changed at 19:50, the camera re-associated by itself and the phone did not — and
it reconnected in the instant the screen was switched on. Android stops scanning with the screen off,
and only behaves this way on battery, which is why it had never happened before. Termux cannot repair
it: `setWifiEnabled()` has been blocked for non-system apps since Android 10.

**A new diagnostic trap.** After the channel change the camera moved from `192.168.101.2` to
`192.168.101.3` by DHCP, and another device (a randomised, locally-administered MAC) took `.2`. The
symptom was deceptive: `.2` answered ping but refused every port, which reads as an AJCloud wedge and
is not one. The camera was perfectly healthy at its new address the whole time. It was found by
sweeping the LAN for port 554 from the phone. **Rule adopted: if the camera "pings but gives no RTSP",
sweep the LAN for 554 before assuming a wedge.**

**Fix.** Deployed live and validated the same day, but **still uncommitted** in the working tree of
`fix/detector-blind-recording`:
- trash emptied (`Used` 14.001 → 9.214 GiB, `Trashed` → 0; the upload backlog drained 56 → 86 clips);
- `--drive-use-trash=false` on both `rclone delete` calls — validated by the 14:37 sweep leaving the
  trash at 0 B and usage down to 9.126 GiB;
- a new `probe_quota()` (`rclone about --json` every `QUOTA_EVERY` = 900 s) publishing `drive_pct`,
  `drive_free_mb`, `drive_total_mb` and `drive_checked` into `sync_status.json`, emitting an event on
  crossing the 90 / 95 / 100 % marks and re-arming when usage falls back;
- app side: `SyncStatus.quotaStep()` plus a step-wise warning in `NewClipsWorker`.

This builds on already-committed plumbing: `aec15ad` (classify sync failures — Drive full / network /
auth / rate) and `44fddd1` (sync-down alert in Salud).

**What it changed about the design.** A cleanup task must verify the **effect** it is supposed to have,
not the operation it performed. Deleting is not freeing — which is the storage-path version of the
health lesson that running is not producing. Second: `status.json` reported `ok`, `recording_ok` and
`detector_ok` all true while nothing had been uploaded for five hours, because the health model stopped
at the NVR and did not include the last mile. Third: there is no preventive warning for a resource that
depletes slowly; a quota needs a trend alarm, not a failure alarm, which is why `probe_quota()` warns
at 90 % rather than at 100 %.

---

## 9. 2026-08-16 — Three separate outages in one day

Three outages on one day, with three unrelated causes. From outside — from the app, from the user's
point of view — all three look identical: **no new clips.**

### 9a. Blind detector, 13 h 40 m (01:25 → ~15:03)

**Symptom.** No clip built at all since `mt_20260816_000635`. ch0 (2K) returning `Invalid data`, so
the segmenter fell back to SUB — which is ch1, the detector's own channel — and the detector failed to
obtain a single frame across 481 retries.

**Hypothesis ruled out — channel contention.** The natural theory, and the one left open on 2026-08-12,
was that the segmenter running in SUB mode was stealing ch1 from the detector. It was tested
experimentally the same day, once the camera was healthy again: an extra `ffprobe` on ch1 *while the
detector was using it* connected (`640,360`, rc=0), and another on ch0 *while the segmenter was
recording* connected too (`2304,1296`, rc=0) — **three concurrent RTSP sessions, no eviction.** The
camera does not limit to one session per channel, consistent with the "session limit ruled out ≥5"
finding from 2026-07-16.

What was actually happening: the camera was **globally degraded** and rejected everything with
`Invalid data` except the segmenter's reconnect. A plausible but unproven explanation of why the
detector lost every time: its backoff (`delay = dfails*8`, up to 60 s) penalised it against the
segmenter's flat 5 s retry. Competing for scarce capacity, whoever waits least wins.

Discarding this hypothesis mattered, because acting on it would have meant changing the fallback logic
— removing SUB mode, or serialising the two consumers — to fix something that was not broken.

**Root cause.** Two facts that hold regardless of any theory about the camera:

1. Recording in SUB with no detector produces **zero clips by definition** — clips are motion-triggered.
   That is 13 h 40 m of battery, disk and CPU spent in a state with no value whatsoever, and nothing in
   the code marked it as anomalous.
2. **The watchdog does not watch the detector.** `kick_stuck_segmenter` (`watchdog.sh:43-54`) only
   checks ring freshness, and with the ring filling perfectly it concluded "all good" for fourteen
   hours and attempted nothing. The datum already existed: `.det_state` / `detector_ok:false` was
   published by the NVR itself, uploaded to Drive, and delivered to the user's phone as a notification
   at 01:25 — and the watchdog running on the same machine never read the file next to it.

**Fix.** `c4ebe40` — `kick_blind_detector` restarts the camera session after `DET_BLIND_KICK` = 300 s
with `.det_state` in DOWN. The guards are the substance, because this is the one component that can
make things *worse* rather than merely wrong — a bug here means a restart loop that records nothing:

| Guard | Why |
|---|---|
| Ping first | An unreachable camera is an external fault no local restart can fix — the same split `reboot_camera()` already made in 2026-07-27. |
| Backoff `300 * 3^n` | Attempts land at ~5, ~20 and ~65 min, not every interval for fourteen hours. |
| Ceiling of 3 attempts | Then stand down and hold the alarm; power-cycling a dead camera forever only burns battery. |
| Reset on recovery | A later unrelated outage does not inherit an exhausted counter. |
| Revive in the same pass | **Found in production, not in the tests.** The first version killed the session and left revival to the next cycle: recording stopped for two full minutes (killed 20:58:26, still down at 21:00:23). Corrected to ~1 s (21:07:05 restart, 21:07:06 revived). Stubbing `tmux` in the tests had hidden exactly the effect that mattered. |

The commit also gives the detector its own retry cap (`DET_RETRY_MAX=20`) instead of sharing the
segmenter's 60 s. The two failures are not equally bad — losing the segmenter costs resolution, losing
the detector costs every clip — yet the shared cap punished the detector hardest.

**Honest limits.** The detector recovered on its own at ~15:03, *before* the manual restart at 15:49;
the restart is not what fixed it, and there is no guarantee the kick will unwedge a degraded camera.
What is certain is that in 13 h 40 m nothing was ever attempted.

**What it changed about the design.** The alert chain was validated end to end by this incident — the
user confirmed the notification arrived (NVR → `detector_ok:false` → Drive → app), so `63d2ef8` works.
But it fired at 01:25 in the morning and the outage still ran nearly fourteen hours. The bottleneck is
therefore **no longer detection or notification; it is auto-recovery with nobody watching.** More
signalling adds nothing at this point. That conclusion reordered the backlog.

### 9b. Zero-second clips

**Symptom.** Clips of ~0 s: ~1.8 KB, one frame. Log signature `window (1s) -> trimmed=1s` followed by
`thumbnail failed`. 18 of them since July; the worst, `mt_20260814_183941`, is 0.092 s.

**Hypothesis ruled out.** That clips appearing "cut mid-motion" after a gap-split were a bug. They are
correct: `run_cend = min(clip_end, run_end)` is right, because the footage genuinely does not exist —
the camera dropped. What was actually wrong was the useless fragment accompanying the good clip.

**Root cause.** When a segmenter run drops, the segment ffmpeg was mid-write keeps no moov atom, so
`ffprobe` returns nothing and `${dseg:-0}` turned that into a **zero-length segment**. The run's window
collapsed to ≤0 s, `render_clip` floored it at 1 s, and out came a one-frame mp4. `build_clip` only
skipped `$newest`, and after a drop the truncated orphan stops being newest, so it sailed straight into
the concat.

The same audit found three more defects, three of which had been shipping silently since the feature
that introduced them:

| # | Defect | Consequence |
|---|---|---|
| 2 | `MIN_CLIP` declared in `record-preroll.sh`, documented in `cam.env.example`, **read by nothing** | Every dropout yielded a good clip *plus* a 1–2 s stub. |
| 3 | The tail-trim (`TAIL_PAD`) never worked: `profile_motion`'s `-ss` sits after `-i`, making it an *output* seek, so the filtergraph sees every frame from the start of the concat and `metadata=print` reports pts on the concat's timeline (verified on the phone: `-ss 10 -t 12` prints pts 0.167 → 22.0). `cut` was absolute while `render_clip` fed it to `-t` as a duration. | The trim overshot by exactly `offset` and usually saturated at the full window — a no-op on every clip with pre-roll. The profiler was also scoring motion from footage *before* the clip: 119 frames counted where the window holds 73. |
| 4 | `compute_battery_eta` read STDIN instead of `$BATTERY_HIST` (the awk had no file argument) | `eta_minutes` has **never once appeared in `status.json`** since it shipped on 2026-07-04. It stayed merely useless rather than fatal only because the NVR starts with stdin at EOF; from an interactive shell that awk blocks, and `write_status()` calls it on every heartbeat, which would wedge the keeper and the whole health path. |

**Fix.** `0b6b112` — unreadable segments are excluded from the concat and the gap-split treats them as
the hole they are; `MIN_CLIP` now floors the gap-split's trailing sliver (the fragment is dropped,
never the event); the trim is rebased in the awk (`if (t < off) next; rt = t - off`); the battery awk
gets its file argument and `</dev/null`.

The same commit brought the first tests the repository ever had: 22 plain-bash unit tests over the
**real** functions — the script is sourced through a new `RECORD_PREROLL_LIB=1` hook with
`ffprobe`/`ffmpeg`/`render_clip` stubbed, so the production `build_clip` is exercised, not a copy —
plus `mutation-check.sh`, which re-introduces each of the four bugs into a scratch copy and requires
the suite to reject it. All four mutants die. bats would have been conventional but is not installed
on the phone, and a suite that cannot be run is worth nothing. CI followed in `063c1d9` (bash tests,
shellcheck, `assembleDebug`).

**Production validation the same night**, during a real drop storm (22:36–23:03): **9 ×
`skipping unreadable ring segment` in 40 minutes** — nine zero-second clips avoided, against roughly
eighteen in the whole preceding month — and the tail-trim visibly trimming for the first time
(24 s → 15.7 s, 47 s → 37.8 s, 23 s → 13.5 s).

**What it changed about the design.** Bug 4 was found **by the tests, not by review** — the same audit
had read that function without seeing it. Three of the four had been shipping silently since
introduction. That is the whole argument for the suite existing, and it is why `mutation-check.sh`
matters more than the test count: it is the only thing that proves the tests would have caught them.
Separately: a parse failure must be distinguishable from a legitimate zero. `${dseg:-0}` collapsed
"could not read this" and "this lasts nothing" into the same value, which is the same shape of mistake
as `read_battery()` omitting its field on 2026-08-01.

### 9c. The camera moved by DHCP (23:05 – 23:25)

**Symptom.** No clips for about 20 minutes; `No route to host`.

**Root cause.** After a ~30-minute internet outage the router restarted and the camera moved from
`192.168.101.3` back to `192.168.101.2` — it had already gone `.2` → `.3` between 08-14 and 08-16.
`cam1.env` holds the address literally, so the NVR was calling an IP that no longer existed.

**Diagnosis.** LAN sweep (`.1`, `.2`, `.4`, `.13`, `.18` alive) plus `ffprobe` against each candidate;
`.2` answered `2304,1296`. Fixed with `sed` on `cam1.env` (backup `cam1.env.bak-20260816`) and a `cam1`
restart.

**Fix.** Still manual. The durable fix — a **DHCP reservation on the router**, or auto-discovery in the
NVR — is pending, and this will recur on the next router restart.

**What it changed about the design.** Nothing yet, and that is the point: it is the second occurrence
of the same cause in three days, and it stays open. It did, however, validate a guard from the same
day's work: the watchdog's ping-first check refused to restart for six consecutive cycles
(`external fault, not restarting`) while the camera was unreachable. Without it, the NVR would have
been restarting itself pointlessly, because no local restart fixes a camera that changed address.

---

## Cross-cutting patterns

Eight things that recurred often enough to be treated as rules rather than anecdotes.

**1. Alive is not producing.** The single most repeated bug in this system. A segmenter run that
outlived `HEALTHY_SECS` but wrote no segment (07-15, 07-16). A detector run that outlived the same bar
but delivered no frames (08-12). A retention sweep that ran perfectly and freed zero bytes (08-14). A
watchdog that saw a fresh ring and concluded the pipeline was working, while nothing was being built
from it (08-16). In every case the metric measured *duration* or *the attempt*, and therefore certified
a total failure as healthy. **Score the work delivered, never the time spent.**

**2. A bug class fixed once and not swept.** The `produced` check fixed the segmenter on 2026-07-16.
The detector kept the duration-based rule until 2026-08-12 and lost 4 h 20 m to it — the identical bug,
in the identical shape, twenty-seven days later. When a class of defect is found, sweep every component
of the same shape immediately, not when it fails.

**3. The signal existed and nothing acted on it.** `reboot_camera()` printed `REBOOT REQUIRED` 68 times
into a local log with no route out (08-12). `.det_state` sat at DOWN for 13 h 40 m while the watchdog
on the same filesystem never opened it (08-16). Publishing a diagnosis is not the same as wiring it to
an actuator, and after 2026-08-16 the actuator — not the sensor, not the notification — is the
bottleneck.

**4. Different faults look identical from outside.** "No new clips" was, on different days: a wedged
camera, a blind detector, a full Drive, an unplugged phone, a failed Wi-Fi re-association, and a DHCP
address change. Nor does any single probe disambiguate — a wedged camera accepts the connection and
returns `Invalid data`; a stolen IP refuses every port; a healthy camera at a new address gives
`No route to host`; and all three answer ping. The diagnostic ladder matters more than any individual
indicator.

**5. Silence is not information.** `read_battery()` omitting its field on timeout (08-01); `ffprobe`
returning empty for a truncated segment and being read as duration 0 (08-16); `MainViewModel.load()`
keeping the old health object when the fetch threw (07-18). In all three, "no value" rendered
downstream as "nothing to report". Failures must be published as values — `battery_unknown`, an
explicitly unreadable segment, an explicit staleness label.

**6. The obvious fix deserves an experiment before it deserves an implementation.** Discarded by
measurement: the HTTP nudge (07-27), channel contention between detector and segmenter (08-16), a
personal Drive `client_id` (07-16), Doze/App Standby (08-14), moving the phone to 5 GHz (08-14),
battery wear (07-15), a charger regression and "the alert never fired" (08-01), the RTSP session limit
and the slow-consumer queue theory (07-16). Several of these were argued confidently before being
tested, and two were retracted only because the user contradicted them. The cost of the experiment was
in every case a fraction of the cost of building the wrong fix.

**7. Instruments have their own failure modes.** Drive thins old file revisions, producing holes that
look exactly like missing uploads (07-18). Stubbing `tmux` in a test hid the two-minute recording gap
that the code under test actually caused (08-16). A `>` continuation prompt silently swallowed three
rounds of commands (08-01). Validate the instrument before trusting the reading.

**8. Configuration describes intent, not reality.** `SEG_TIME=4` against a measured ~12 s GOP (07-20).
The deployed `POSTROLL=12` against 3 in the example file (07-20). A camera IP written literally into
`cam1.env` against a DHCP lease that moved twice in three days (08-14, 08-16). `MIN_CLIP` documented in
two places and read by none (08-16). Derive behaviour from the measured artefact, and treat any
configuration value that nothing reads as a defect.
