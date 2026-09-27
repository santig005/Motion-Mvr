package com.famviva.camara.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "did you unplug it?" prompt: asks once per episode after 6 h, never for the wrong reason. */
class SilentPromptTest {

    private val now = 1_790_000_000L
    private fun health(ok: Boolean, updated: Long = now, wedged: Boolean = false, disabled: Boolean = false) =
        CameraHealth(camera = "Camara2", ok = ok, updated = updated, cameraWedged = wedged, disabled = disabled)

    @Test fun `only a camera that is down while the NVR reports counts as silent`() {
        assertTrue(isSilentForPrompt(health(ok = false), now))
        assertFalse(isSilentForPrompt(health(ok = true), now))                      // recording fine
        assertFalse(isSilentForPrompt(health(ok = false, updated = now - 86_400), now)) // NVR phone down
        assertFalse(isSilentForPrompt(health(ok = false, wedged = true), now))     // needs a power-cycle
        assertFalse(isSilentForPrompt(health(ok = false, disabled = true), now))   // already switched off
    }

    @Test fun `asks once, after six hours of the same episode`() {
        var st = emptyMap<String, SilentState>()
        var asked = emptyList<String>()
        for (t in listOf(0L, 3_600L, 5 * 3_600L)) {
            stepSilent(st, setOf("Camara2"), now + t).let { st = it.first; asked = it.second }
            assertEquals(emptyList<String>(), asked)
        }
        stepSilent(st, setOf("Camara2"), now + SILENT_ASK_SECS).let { st = it.first; asked = it.second }
        assertEquals(listOf("Camara2"), asked)
        stepSilent(st, setOf("Camara2"), now + 2 * SILENT_ASK_SECS).let { st = it.first; asked = it.second }
        assertEquals(emptyList<String>(), asked)                                   // never twice per episode
    }

    @Test fun `a recovery ends the episode, so the next outage starts from zero`() {
        val (s1, _) = stepSilent(emptyMap(), setOf("Camara2"), now)
        val (s2, _) = stepSilent(s1, emptySet(), now + 60)
        assertTrue(s2.isEmpty())
        val (_, ask) = stepSilent(s2, setOf("Camara2"), now + SILENT_ASK_SECS)   // new episode: only just began
        assertEquals(emptyList<String>(), ask)
    }

    @Test fun `still installed silences the prompt for this episode`() {
        val st = mapOf("Camara2" to SilentState(since = now, keep = true))
        assertEquals(emptyList<String>(), stepSilent(st, setOf("Camara2"), now + 2 * SILENT_ASK_SECS).second)
    }

    @Test fun `episodes survive being saved and loaded`() {
        val st = mapOf("Camara1" to SilentState(10, asked = true), "Camara2" to SilentState(20, keep = true))
        assertEquals(st, decodeSilent(encodeSilent(st)))
        assertTrue(decodeSilent("garbage\n|x|1|0").isEmpty())
    }
}
