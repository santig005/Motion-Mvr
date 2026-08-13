# Roadmap — where the project stands and what comes next

Status: **strategic snapshot, 2026-08-02.** Written after auditing the July backlog against the code
actually shipped, then extended the same day with a repository-quality audit (see *Engineering
hardening*). Percentages are honest estimates, not metrics.

## Where we are

Almost everything originally planned is built. Of the ~20 items in the July 2026 backlog, only three
were never done: real multi-camera, two-way audio/PTZ, and object detection.

| Phase | State | Notes |
|---|---|---|
| 1. NVR core (pre-roll, ring buffer, motion detect) | ✅ 100% | |
| 2. Cloud sync + tiered retention | ✅ 100% | 3-lane sync, favourites-aware purge |
| 3. Consumer app (browse / play / offline) | ✅ 100% | |
| 4. Observability (Salud, events.jsonl, alerts) | ✅ ~95% | |
| 5. Live view (LAN + Tailscale) | ✅ ~90% | SD solid; 2K historically flaky |
| 6. Local archive, 3-tier retention (Phases A+B) | ✅ 100% | `LOCAL_ARCHIVE_PLAN.md` |
| 7. Self-healing / resilience | 🟡 ~80% | camera auto-reboot still a hook |
| 8. Intelligence (object detection) | ⬜ 0% | **next up** |
| 9. Power resilience | ⬜ 0% | hardware, cheap |
| —. Engineering hardening (tests, CI, structure) | ⬜ 0% | separate track, see below |

Overall: **~90% of the original vision.** What remains is qualitatively different from what was
built — it is not more of the same.

## The ceiling we hit

The system does exactly what it set out to do: record everything that moves and present it well.
That is now the problem.

A representative day produces ~73 clips. Perhaps two of them are a person. The rest are shadows,
insects, rain and moving vegetation. The existing mitigations (intensity gate, quiet hours) are
heuristics over pixel brightness — they cannot tell a person from a shadow.

Everything built so far (archive, health, widget, timeline) is excellent infrastructure **in service
of a list that is mostly noise.** The bottleneck is no longer engineering; it is signal-to-noise.

## Engineering hardening — a separate track ⬅️ next session

The feature list below is about what the system *does*. This section is about what the repository
*is*, and it is deliberately kept apart: none of it changes behaviour for the user, and all of it
changes how the work reads to anyone else — including a reviewer or an interviewer.

The honest state, from an audit on 2026-08-02: 73 commits, ~9.4k lines of Kotlin, ~1.3k of bash,
**zero tests and zero CI**. Every fix so far was validated by watching the phone. That has worked
because there is one developer who remembers everything, and it stops working the moment that is no
longer true. Three of the four items below cost less than a day each.

Do them in this order — 1 and 2 are the ones that matter, 3 is cheap, 4 is the highest-value writing
in the repo.

### H1. Minimal CI on GitHub Actions (~1 h)

A single workflow that, on push and PR:
- runs `shellcheck termux/*.sh` — 1.3k lines of bash have been running 24/7 without a linter, and it
  is the layer where a silent breakage costs actual footage;
- runs `assembleDebug` for `consumer-app/`.

Note the interaction with the offline-build constraint (`_private/build-and-deploy.md`): CI builds
*online*, which is fine and in fact useful — it becomes the place that proves the dependency set
still resolves from scratch, something the local `--offline` build can never tell us.

Start with `continue-on-error` on shellcheck if the first run is noisy, then tighten. A green badge
in the README is worth more than the workflow costs.

### H2. Unit tests over the pure logic (~half a day)

Not UI tests. The valuable, trivially testable surface is the decision logic that has already caused
real incidents:

- **gap-split** — the end→start hole rule (`9a8b916`); the bug it fixed was a *false* split, so the
  test must cover both "real dropout → two clips" and "normal ~12 s GOP spacing → one clip".
- **content-based tail trim** — `last_motion + TAIL_PAD`, including the 360p↔2K skew case.
- **`BatteryForecast`** — the discharge regression and the ETA to `BATTERY_FLOOR_PCT`, plus the
  `battery_unknown` path (`ab5a750`).
- **`status.json` / `events.jsonl` parsing** — malformed, truncated and missing-field rows must
  degrade, never crash.
- **wedge classification** — the state machine that decides a camera is wedged rather than merely
  reconnecting (`5de12b7`).

⚠️ **Expect a blocker on anything ViewModel-shaped.** `MainViewModel` takes nine concrete
collaborators (`DriveClient`, `SeenStore`, `OfflineStore`, `ClipListCache`, …) with no interfaces and
no DI, so it cannot be faked. Do **not** start by fixing that — start with the pure functions above,
which need none of it. Extracting interfaces (or introducing Hilt) is a real refactor and belongs in
its own session, justified by the tests that then become possible.

For the bash side, the cheapest useful thing is a handful of `bats` cases over the clip-boundary
maths, not a full harness.

### H3. Split `ui/AppNav.kt` (~2–3 h, mechanical)

3,834 lines and 78 composables in one file — about **40 % of all the Kotlin in the app.** It holds
navigation, the Days screen, Storage (donut, legend, per-day), Clips, the filmstrip, dialogs and the
overflow menu.

Split by screen into `ui/days/`, `ui/storage/`, `ui/clips/`, `ui/health/`, leaving `AppNav.kt` as
just the nav graph and the bottom bar. Pure file movement, no behaviour change — but do it in its own
commit, and ideally *after* H1 exists so the build check has something to say about it.

### H4. `INCIDENTS.md` — the most valuable document not yet written

The material already exists, scattered across project memory and commit messages. Collect it into one
file, one entry per real incident, in a fixed shape: **symptom → hypotheses ruled out → root cause →
fix (with commit) → what it changed about the design.**

The ones worth writing up:

| Date | Incident | Why it is worth writing |
|---|---|---|
| 2026-07-15 | Slow charge / RTSP reconnect storm | ~45 mA net charge; led to the flap guard |
| 2026-07-16 | Upload lag = Drive 403 quota cascade | Produced the 3-lane sync design |
| 2026-07-18 | "5 h without reporting" | **A false alarm** — an app-side re-fetch bug, not the NVR |
| 2026-07-20 | Clips split in two | The split rule itself was wrong (`9a8b916`) |
| 2026-07-27 | Camera wedged 12 h | HTTP-nudge investigated and *discarded*; wedge classifier shipped |
| 2026-08-01 | 21 h outage | Not the battery: Android throttled Termux, so Termux:Boot never ran |

Two of those entries are worth more than the other four: the one where the alarm was wrong, and the
one where the obvious fix was investigated and rejected. Keep them.

## Next steps, in order

*(product/feature track — the hardening track above runs alongside it)*

### 1. Object detection on the Pixel — NO new hardware ⬅️ start here

The Pixel 9a is the "mini PC" the project already owns: a Tensor G4 with an NPU, idle most of the
day, and — crucially — **it already downloads every clip's thumbnail** (`ThumbArchive.kt`, shipped in
local-archive Phase A). The classifier's input is already on the device, for free.

Approach: run a small object detector (ML Kit Object Detection, or a TFLite MobileNet/EfficientDet)
over the thumbnail the app already fetches, label each clip *person / vehicle / animal / none*, and
use that to gate alerts. Inference is ~30 ms on that chip.

**Why post-hoc classification loses nothing here:** the obvious objection is that Frigate detects in
real time while this runs after the clip exists. But the measured end-to-end latency of this pipeline
is already **~114 s** from content end to visible on Drive (see `_private/phone-access.md`). Alerts
already arrive ~2 minutes late. Classifying the thumbnail adds about one second to that. In practice
the difference is not observable.

Suggested sequencing, lowest risk first:
1. Classify + **gate notifications** only (never suppress recording — the clip is always kept).
2. Persist the label on `ClipRecord` and add filter chips ("people only").
3. Backfill labels over the existing thumbnail archive → the history becomes searchable.

Never let the classifier decide what gets *recorded*. It only decides what gets *announced*. A false
negative must cost an alert, never footage.

⚠️ **Known build obstacle:** the app builds with `gradle --offline` against the local cache. That is
exactly why Room was rejected in local-archive Phase A (hence `CatalogStore.kt` is JSON, not Room —
see `_private/build-and-deploy.md`). Adding ML Kit/TFLite **requires one online build** to populate
the Gradle cache. Not a blocker, but it must be done deliberately.

### 2. Mini DC UPS for the camera + router — cheap, closes the last resilience gap

The intuitive move is to back up the NVR phone. That is the wrong target: **the NVR phone already has
a battery, and it is the least of the problem.** When mains power fails, the *camera* dies and the
*router* dies — so there is nothing to record and no network to carry it. The phone's battery keeps
alive the one component that has nothing left to do.

What to back up: camera (~4 W) + router (~10 W) ≈ **15 W**. That does not need a 600 VA tower UPS; a
**mini DC UPS for routers** (lithium pack with 12 V / 9 V / 5 V outputs) carries that load for hours
at a fraction of the price. Uploads pause during the outage and `cloud-sync.sh` drains the backlog on
reconnect — that path already works.

### 3. Smart plug on the camera — closes the wedged-camera case

`reboot_camera()` in `record-preroll.sh` is already written and already called by the wedge detector;
it just has a `TODO(reboot)` where the actual power-cycle goes. A smart plug fills it in one line.
The alternative — reverse-engineering the vendor P2P protocol — was investigated at length and judged
a dedicated multi-session effort (see project memory). The plug is the cheap, reliable close.

### 4. Multi-camera — see `MULTICAMERA_PLAN.md`

The user owns 4 cameras; 1 is installed (electrical constraints). Full design, the enable/disable
mechanism and the **hard capacity limits** are in that document.

### 5. Mini PC / Frigate — re-evaluate in ~3 months, not now

Honest economics (Colombia, 2026): ~300,000 COP (≈70 USD) does **not** buy a new N100 mini PC (those
run 600–750k COP); it buys a used thin client or an ex-office micro PC.

**Power consumption is not the obstacle it seems.** An N100 running Frigate draws 10–20 W ≈ 14 kWh per
month ≈ **~11,000 COP/month** at ~800 COP/kWh. The cost is the box, not the electricity.

Recommendation: do step 1 first. If in three months you genuinely need real-time detection, several
simultaneous cameras, or continuous 24/7 recording, the mini PC will justify itself — and you will
know precisely why. Buying it now would be buying a solution before the problem is proven.

## The long-term vision

Today the system reports *"there was motion"* 73 times a day. The destination is a system that
reports **three things a day, and all three matter**:

- Not "motion detected" but **"a person approached the door at 3:14 pm"**, with the person cropped —
  not a full frame where you have to hunt for what changed.
- **Silent at night** unless someone is actually there. Insects, rain and shadows still get recorded;
  they simply never wake you.
- A daily summary that is a story, not a count: *"today: 3 people, 1 vehicle, 47 dismissed as
  vegetation."*
- An archive you can **question** — "show me people at night last week" — so it becomes the
  searchable memory of the house rather than a pile of clips.
- And it looks after itself: camera wedges → power-cycled automatically; mains fails → keeps
  recording; something breaks → you know in minutes, not in 21 hours (see the 2026-08-01 outage).

The through-line: **the system stops reporting pixels and starts reporting events.** Everything built
so far is the right infrastructure for that. What is missing is the layer that understands what it is
looking at.
