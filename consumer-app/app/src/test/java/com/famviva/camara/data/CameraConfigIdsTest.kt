package com.famviva.camara.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The camera list behind multi-camera live view is a stored id string; these pin its round trip. */
class CameraConfigIdsTest {

    @Test fun `ids keep their order and drop blanks and duplicates`() {
        assertEquals(listOf("Camara1", "Camara2"), parseCameraIds("Camara1, ,Camara2,Camara1"))
        assertEquals(emptyList<String>(), parseCameraIds(null))
        assertEquals(emptyList<String>(), parseCameraIds(""))
    }

    @Test fun `adding an existing camera does not duplicate it`() {
        assertEquals("Camara1,Camara2", joinCameraIds(listOf("Camara1", "Camara2", "Camara1")))
    }

    @Test fun `removing a camera keeps the others in order`() {
        assertEquals("Camara1,Camara3", joinCameraIds(parseCameraIds("Camara1,Camara2,Camara3") - "Camara2"))
    }

    @Test fun `ids that would break the list or the keys are rejected`() {
        assertTrue(isValidCameraId("Camara2"))
        assertFalse(isValidCameraId(""))
        assertFalse(isValidCameraId("Cam,2"))
        assertFalse(isValidCameraId("Cam 2"))
        assertFalse(isValidCameraId("cam.2"))
    }
}
