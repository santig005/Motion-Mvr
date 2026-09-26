package com.famviva.camara.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The link verdict replaced the RSSI one after 2026-09-22, when Android's screen-off RSSI read a
 * "normal" −72 dBm through a night of ~50 recording drops. These pin the thresholds to the two
 * measurements that calibrated them, so a later tweak can't silently call that night "ok" again.
 */
class LinkQualityTest {

    @Test fun `the healthy link measured after the fix is GOOD`() {
        assertEquals(WifiQuality.GOOD, linkQualityOf(0, 5))
    }

    @Test fun `the 2026-09-22 drop-storm link is WEAK`() {
        assertEquals(WifiQuality.WEAK, linkQualityOf(5, 368))
    }

    @Test fun `heavy loss is WEAK even when the replies that do arrive are fast`() {
        assertEquals(WifiQuality.WEAK, linkQualityOf(10, 4))
    }

    @Test fun `no reply at all is WEAK, not unknown`() {
        assertEquals(WifiQuality.WEAK, linkQualityOf(100, null))
    }

    @Test fun `one lost ping in twenty with a fast tail is only OK`() {
        assertEquals(WifiQuality.OK, linkQualityOf(5, 12))
    }

    @Test fun `a slowish tail without loss is OK`() {
        assertEquals(WifiQuality.OK, linkQualityOf(0, 80))
    }

    @Test fun `older NVR builds without the probe have no verdict`() {
        assertNull(linkQualityOf(null, null))
    }
}
