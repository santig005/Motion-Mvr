package com.famviva.camara.ui

import com.famviva.camara.data.LaneState
import com.famviva.camara.data.TimelineSpan

/**
 * Pure geometry for the health screen's coverage swimlane.
 *
 * Split out of AppNav.kt for two reasons: it is the one part of that screen with real logic rather
 * than layout, and keeping it free of Compose imports makes it unit-testable on the JVM — the bug it
 * fixes was invisible to the eye and would have been caught instantly by a test.
 */

/** One band as actually PAINTED: pixel geometry plus the span(s) it stands for. [spans] holds more
 *  than one when neighbours were too close to draw separately — that is a cluster. */
internal data class LaidOutBand(
    val left: Float,
    val width: Float,
    val spans: List<TimelineSpan>,
    val state: LaneState,
)

/** Worst-first ranking, so a cluster is coloured (and labelled) by its most severe member: a 3-minute
 *  outage folded in with some reconnects must not be disguised as "just reconnects". */
internal fun severityRank(s: LaneState): Int = when (s) {
    LaneState.DOWN -> 5
    LaneState.FLAP_CRITICAL -> 4
    LaneState.FLAP_SERIOUS -> 3
    LaneState.DEGRADED -> 2
    LaneState.SYNC_WARN -> 1
    LaneState.OK -> 0
}

/**
 * Turns spans into the bands to paint.
 *
 * This exists because the original code widened any sub-pixel span to a 2.5dp minimum but still drew
 * it at its exact proportional offset, so two events closer together than that minimum were painted
 * ON TOP of each other and the earlier one simply vanished. At the 24h horizon that minimum is about
 * 14 minutes of real time (~1.7 hours at 7d), which is why a burst of reconnects collapsed into a
 * single band and every other event in the burst became unreachable.
 *
 * Rules: a band wide enough to stand on its own keeps its exact geometry. A band too narrow AND
 * overlapping what is already painted is folded into that band, becoming a cluster, instead of
 * overpainting it. Order is preserved and nothing is ever hidden.
 *
 * Being a PURE function of (spans, window, width) is the point: the draw pass and the tap handler
 * both call it and therefore agree exactly. The original tap handler hit-tested against TIME rather
 * than against what had been drawn, so tapping a visible band could open a different event's detail.
 */
internal fun layoutBands(
    spans: List<TimelineSpan>,
    windowStart: Long,
    windowSpan: Long,
    widthPx: Float,
    minPx: Float,
): List<LaidOutBand> {
    if (spans.isEmpty() || widthPx <= 0f || windowSpan <= 0L) return emptyList()
    val out = ArrayList<LaidOutBand>(spans.size)
    val members = ArrayList<ArrayList<TimelineSpan>>(spans.size)
    var cursor = 0f
    spans.forEach { s ->
        val left0 = ((s.startTs - windowStart).toFloat() / windowSpan.toFloat()) * widthPx
        val natural = ((s.endTs - s.startTs).toFloat() / windowSpan.toFloat()) * widthPx
        val prev = out.lastOrNull()
        if (natural < minPx && prev != null && left0 < cursor) {
            val group = members.last()
            group.add(s)
            val worst = if (severityRank(s.state) > severityRank(prev.state)) s.state else prev.state
            out[out.size - 1] = prev.copy(spans = group, state = worst)
        } else {
            val w = maxOf(natural, minPx)
            val left = maxOf(left0, cursor).coerceIn(0f, (widthPx - w).coerceAtLeast(0f))
            out.add(LaidOutBand(left, w, listOf(s), s.state))
            members.add(arrayListOf(s))
            cursor = left + w
        }
    }
    return out
}
