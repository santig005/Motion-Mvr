package com.famviva.camara.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The person-detection gate is pure arithmetic over labels — the same shape of decision logic that
 * has caused every clip-alert regression, and now the layer that decides whether a real intrusion is
 * announced. It must be testable without a model or an emulator, so this covers the COCO mapping, the
 * "most important thing in the frame" reduction, and the fail-open gate.
 */
class ClipLabelTest {

    @Test fun `coco names map to the household buckets`() {
        assertEquals(ClipLabel.PERSON, ClipLabel.ofCoco("person"))
        assertEquals(ClipLabel.VEHICLE, ClipLabel.ofCoco("car"))
        assertEquals(ClipLabel.VEHICLE, ClipLabel.ofCoco("truck"))
        assertEquals(ClipLabel.ANIMAL, ClipLabel.ofCoco("dog"))
        assertEquals(ClipLabel.ANIMAL, ClipLabel.ofCoco("cat"))
        // Anything COCO knows but we don't care about is noise, not a mystery.
        assertEquals(ClipLabel.NONE, ClipLabel.ofCoco("potted plant"))
        assertEquals(ClipLabel.NONE, ClipLabel.ofCoco("chair"))
    }

    @Test fun `mapping is case and whitespace insensitive`() {
        assertEquals(ClipLabel.PERSON, ClipLabel.ofCoco("  Person "))
        assertEquals(ClipLabel.VEHICLE, ClipLabel.ofCoco("BUS"))
    }

    @Test fun `summarize picks the most important label in the frame`() {
        // A person standing next to a parked car is a PERSON clip, not a vehicle clip.
        assertEquals(ClipLabel.PERSON, ClipLabel.summarize(listOf("car", "person", "dog")))
        assertEquals(ClipLabel.VEHICLE, ClipLabel.summarize(listOf("dog", "truck")))
        assertEquals(ClipLabel.ANIMAL, ClipLabel.summarize(listOf("dog", "chair")))
    }

    @Test fun `an empty detection set is a positive NONE, not a null`() {
        // The detector looked and named nothing — that is a real verdict the gate can act on.
        assertEquals(ClipLabel.NONE, ClipLabel.summarize(emptyList()))
    }

    @Test fun `gate is a no-op when the people-only filter is off`() {
        for (label in listOf(ClipLabel.PERSON, ClipLabel.VEHICLE, ClipLabel.ANIMAL, ClipLabel.NONE, null)) {
            assertTrue(passesLabelGate(label, peopleOnly = false))
        }
    }

    @Test fun `people-only passes a person and blocks the noise buckets`() {
        assertTrue(passesLabelGate(ClipLabel.PERSON, peopleOnly = true))
        assertFalse(passesLabelGate(ClipLabel.VEHICLE, peopleOnly = true))
        assertFalse(passesLabelGate(ClipLabel.ANIMAL, peopleOnly = true))
        assertFalse(passesLabelGate(ClipLabel.NONE, peopleOnly = true))
    }

    @Test fun `people-only fails OPEN on an unknown label`() {
        // Classifier unavailable (no model, decode error): never swallow a possible intrusion.
        assertTrue(passesLabelGate(null, peopleOnly = true))
    }
}
