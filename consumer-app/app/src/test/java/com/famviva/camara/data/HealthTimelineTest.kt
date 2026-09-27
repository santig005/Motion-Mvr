package com.famviva.camara.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Outage pairing on the Health timeline. */
class HealthTimelineTest {

    private fun ev(ts: Long, svc: String, e: String, cam: String? = "Camara2") = OutageEvent(ts, cam, svc, e)

    @Test fun `the ring detector standing down is not an outage`() {
        // The real 2026-09-27 sequence: recording recovered, then the fallback detector stood down
        // because the RTSP detector was back. Nothing is still "happening".
        val tl = buildHealthTimeline(
            listOf(
                ev(100, "recording", "down"),
                ev(200, "ring_detector", "up"),     // fallback ENGAGED (RTSP detector died)
                ev(300, "recording", "up"),
                ev(310, "ring_detector", "down"),   // fallback stood down (RTSP detector back)
            ),
        )
        val open = tl.filterIsInstance<OutageEntry>().filter { it.outage.endTs == null }
        assertTrue("no phantom ongoing outage: $open", open.isEmpty())
        assertEquals(1, tl.filterIsInstance<OutageEntry>().size)            // the real recording outage
        assertEquals(2, tl.filterIsInstance<BlipEntry>().count { it.event.svc == RING_DETECTOR_SVC })
    }

    @Test fun `a real down without its up is still shown as ongoing`() {
        val tl = buildHealthTimeline(listOf(ev(100, "recording", "down")))
        assertEquals(null, (tl.single() as OutageEntry).outage.endTs)
    }

    @Test fun `events from several files pair by time, not by file order`() {
        val tl = buildHealthTimeline(listOf(ev(300, "recording", "up"), ev(100, "recording", "down")))
        assertEquals(300L, (tl.single() as OutageEntry).outage.endTs)
    }
}
