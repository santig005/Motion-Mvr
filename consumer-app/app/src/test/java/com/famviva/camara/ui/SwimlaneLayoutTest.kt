package com.famviva.camara.ui

import com.famviva.camara.data.LaneState
import com.famviva.camara.data.TimelineSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first JVM unit tests in the app module. They cover `layoutBands`, the swimlane geometry — the
 * one piece of the health screen that is real logic rather than layout, and the source of a bug that
 * was invisible to the eye: bands narrower than the minimum width were drawn at their exact
 * proportional offset, so neighbours painted over each other and the earlier event disappeared.
 *
 * These run on the JVM with no Android framework and no device, because SwimlaneLayout.kt was kept
 * free of Compose imports precisely so they could.
 *
 * NOTE: junit is not in the local Gradle cache and the local build is `--offline` (the same
 * constraint that ruled out Room), so these are executed by CI, which builds online.
 */
class SwimlaneLayoutTest {

    private val w = 250f          // a realistic lane width in px after the 92dp label column
    private val minPx = 3f
    private val day = 86_400L     // the 24h horizon
    private val t0 = 1_786_900_000L

    private fun span(fromSec: Long, toSec: Long, state: LaneState = LaneState.DOWN, flaps: Int = 0) =
        TimelineSpan(t0 + fromSec, t0 + toSec, state, flaps)

    private fun layout(vararg spans: TimelineSpan) =
        layoutBands(spans.toList(), t0, day, w, minPx)

    // --- the regression this file exists for ---------------------------------------------------

    @Test
    fun `two events closer than the minimum width do not overlap`() {
        // 60s apart on a 24h window is ~0.17px — far below the 3px minimum. The old code drew both
        // at their exact offsets, so the second covered the first entirely.
        val bands = layout(span(3600, 3610), span(3660, 3670))
        bands.zipWithNext { a, b ->
            assertTrue("bands must not overlap: $a then $b", b.left >= a.left + a.width - 0.01f)
        }
    }

    @Test
    fun `events too close to draw apart are clustered, never dropped`() {
        val bands = layout(span(3600, 3610), span(3660, 3670), span(3720, 3730))
        assertEquals("they collapse into a single cluster", 1, bands.size)
        assertEquals("and it accounts for all three", 3, bands[0].spans.size)
    }

    @Test
    fun `every input span is represented in the output`() {
        val spans = (0 until 25).map { span(it * 60L, it * 60L + 10L) }
        val bands = layoutBands(spans, t0, day, w, minPx)
        assertEquals(spans.size, bands.sumOf { it.spans.size })
    }

    // --- clusters must not disguise severity ---------------------------------------------------

    @Test
    fun `a cluster takes the severity of its worst member`() {
        val bands = layout(
            span(3600, 3610, LaneState.FLAP_SERIOUS),
            span(3660, 3670, LaneState.DOWN),          // the one that matters
            span(3720, 3730, LaneState.FLAP_SERIOUS),
        )
        assertEquals(1, bands.size)
        assertEquals(LaneState.DOWN, bands[0].state)
    }

    @Test
    fun `severity ranking puts DOWN above every flap state`() {
        assertTrue(severityRank(LaneState.DOWN) > severityRank(LaneState.FLAP_CRITICAL))
        assertTrue(severityRank(LaneState.FLAP_CRITICAL) > severityRank(LaneState.FLAP_SERIOUS))
        assertTrue(severityRank(LaneState.FLAP_SERIOUS) > severityRank(LaneState.OK))
    }

    // --- wide bands must keep their honest geometry --------------------------------------------

    @Test
    fun `a wide span keeps its proportional width and position`() {
        val bands = layout(span(0, day / 2))            // exactly half the window
        assertEquals(1, bands.size)
        assertEquals(w / 2f, bands[0].width, 0.5f)
        assertEquals(0f, bands[0].left, 0.5f)
    }

    @Test
    fun `a wide span is never folded into a cluster`() {
        val bands = layout(span(0, 600), span(3600, day / 2))
        assertEquals(2, bands.size)
        assertEquals(1, bands[1].spans.size)
    }

    // --- geometry invariants --------------------------------------------------------------------

    @Test
    fun `no band is narrower than the minimum or escapes the lane`() {
        val spans = (0 until 40).map { span(it * 900L, it * 900L + 5L) }
        layoutBands(spans, t0, day, w, minPx).forEach {
            assertTrue("width ${it.width} < min", it.width >= minPx - 0.01f)
            assertTrue("left ${it.left} negative", it.left >= -0.01f)
            assertTrue("band overflows lane", it.left + it.width <= w + 0.01f)
        }
    }

    @Test
    fun `bands stay in chronological order`() {
        val spans = (0 until 30).map { span(it * 400L, it * 400L + 30L) }
        val bands = layoutBands(spans, t0, day, w, minPx)
        bands.zipWithNext { a, b -> assertTrue(b.left >= a.left) }
        val firsts = bands.map { it.spans.first().startTs }
        assertEquals(firsts.sorted(), firsts)
    }

    @Test
    fun `degenerate inputs yield nothing rather than crashing`() {
        assertTrue(layoutBands(emptyList(), t0, day, w, minPx).isEmpty())
        assertTrue(layoutBands(listOf(span(0, 10)), t0, day, 0f, minPx).isEmpty())
        assertTrue(layoutBands(listOf(span(0, 10)), t0, 0L, w, minPx).isEmpty())
    }

    // --- the real 2026-08-16 shape ---------------------------------------------------------------

    @Test
    fun `the 2026-08-16 reconnect storm stays reachable at 24h and separates at 6h`() {
        // Roughly what happened between 22:36 and 23:03: a burst of short drops inside ~27 minutes.
        val storm = (0 until 9).map { span(1600L + it * 180L, 1600L + it * 180L + 20L) }

        // At 24h the burst has to collapse — 9 events inside 24 minutes cannot be drawn apart on a
        // 250px lane. What matters is that collapsing never LOSES one, which is exactly what the old
        // overpainting did. (It yields 2 bands, not 1: the last event finally clears the cursor.)
        val at24h = layoutBands(storm, t0, day, w, minPx)
        assertTrue("the burst must collapse at 24h", at24h.size < storm.size)
        assertEquals("but all nine stay reachable", 9, at24h.sumOf { it.spans.size })

        // Zooming in is the only thing that genuinely separates them, which is why the 6h horizon
        // exists: clustering alone cannot fix density.
        val at6h = layoutBands(storm, t0, 6 * 3600L, w, minPx)
        assertTrue("6h separates more than 24h does", at6h.size > at24h.size)
        assertEquals(9, at6h.sumOf { it.spans.size })
    }
}
