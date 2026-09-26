package com.famviva.camara.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The app used to drop the camera from a clip's Drive path and pool every mt_* together — so the
 * 284 clips of the second (test) camera showed up as if they were the entrance camera's. These pin
 * down how the camera is recovered from the folder chain.
 */
class CameraPathTest {

    // Camaras/Camara1/2026/09/26 and Camaras/Camara2/2026/09/26, like the real Drive.
    private val folders = mapOf(
        "root" to DriveFolder("Camaras", null),
        "c1" to DriveFolder("Camara1", "root"),
        "c1y" to DriveFolder("2026", "c1"),
        "c1m" to DriveFolder("09", "c1y"),
        "c1d" to DriveFolder("26", "c1m"),
        "c2" to DriveFolder("Camara2", "root"),
        "c2y" to DriveFolder("2026", "c2"),
        "c2m" to DriveFolder("09", "c2y"),
        "c2d" to DriveFolder("26", "c2m"),
    )

    @Test fun `a clip in a day folder belongs to the camera above the date folders`() {
        assertEquals("Camara1", cameraOfFolder("c1d", folders))
        assertEquals("Camara2", cameraOfFolder("c2d", folders))
    }

    @Test fun `a file directly in the camera folder (metrics csv) resolves to that camera`() {
        assertEquals("Camara2", cameraOfFolder("c2", folders))
    }

    @Test fun `unknown folders fall back to no camera, never a wrong one`() {
        assertNull(cameraOfFolder(null, folders))
        assertNull(cameraOfFolder("missing", folders))
        // A day folder whose ancestors are not in the listing (e.g. a partial page) stays unknown.
        assertNull(cameraOfFolder("orphan", folders + ("orphan" to DriveFolder("26", "gone"))))
    }

    @Test fun `a folder cycle cannot hang the walk`() {
        val loop = mapOf("a" to DriveFolder("26", "b"), "b" to DriveFolder("09", "a"))
        assertNull(cameraOfFolder("a", loop))
    }

    @Test fun `camera-tagged clip names keep parsing date and time`() {
        val c = Clip(id = "x", name = "mt_20260926_125715_Camara2.mp4", sizeBytes = 1L)
        assertEquals("20260926", c.dateKey)
        assertEquals("12:57:15", c.time)
        val r = ClipRecord(name = "mt_20260926_125715_Camara2")
        assertEquals("20260926", r.dateKey)
        assertEquals("12:57:15", r.time)
    }
}
