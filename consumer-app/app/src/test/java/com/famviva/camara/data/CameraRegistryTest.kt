package com.famviva.camara.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The enable/disable switch must fail OPEN (an unknown camera keeps being watched — a stolen camera
 * must never quietly count as "switched off"), and a local edit must not be lost to, nor overwrite, a
 * newer copy on Drive.
 */
class CameraRegistryTest {

    private val reg = CameraRegistry(
        updated = 100,
        cameras = mapOf(
            "Camara1" to CameraEntry(enabled = true, label = "Pasillo Interior"),
            "Camara2" to CameraEntry(enabled = false, label = "  "),
        ),
    )

    @Test fun `unknown cameras and no registry at all are enabled`() {
        assertTrue(reg.isEnabled("Camara9"))
        assertTrue(CameraRegistry().isEnabled("Camara1"))
        assertTrue(reg.isEnabled(null))
        assertTrue(reg.isEnabled("Camara1"))
        assertFalse(reg.isEnabled("Camara2"))
    }

    @Test fun `the alias shows when set, the id otherwise`() {
        assertEquals("Pasillo Interior", reg.labelOf("Camara1"))
        assertEquals("Camara2", reg.labelOf("Camara2"))      // blank alias = no alias
        assertEquals("Camara9", reg.labelOf("Camara9"))
    }

    @Test fun `an edit changes one camera and bumps the timestamp`() {
        val e = reg.edit("Camara2", now = 200) { it.copy(enabled = true, label = "Habitación") }
        assertEquals(200, e.updated)
        assertTrue(e.isEnabled("Camara2"))
        assertEquals("Habitación", e.labelOf("Camara2"))
        assertEquals("Pasillo Interior", e.labelOf("Camara1"))
        val added = reg.edit("Camara3", now = 300) { it.copy(enabled = false) }
        assertFalse(added.isEnabled("Camara3"))                // a new entry starts from the defaults
    }

    @Test fun `a pending local edit newer than Drive is kept and pushed`() {
        val local = reg.copy(updated = 500)
        val (keep, push) = reconcileRegistry(local, reg, pendingUpload = true)
        assertSame(local, keep); assertTrue(push)
        val (keep2, push2) = reconcileRegistry(local, null, pendingUpload = true)
        assertSame(local, keep2); assertTrue(push2)
    }

    @Test fun `otherwise Drive wins, and nothing is pushed`() {
        val remote = reg.copy(updated = 900)
        assertSame(remote, reconcileRegistry(reg, remote, pendingUpload = true).first)   // newer edit elsewhere
        assertSame(remote, reconcileRegistry(reg.copy(updated = 999), remote, pendingUpload = false).first)
        assertFalse(reconcileRegistry(reg, remote, pendingUpload = false).second)
        assertSame(reg, reconcileRegistry(reg, null, pendingUpload = false).first)
    }
}
