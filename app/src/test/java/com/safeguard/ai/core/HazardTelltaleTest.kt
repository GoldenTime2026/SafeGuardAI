package com.safeguard.ai.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardTelltaleTest {

    @Test
    fun bothTurnSignalsMeanHazard() {
        assertTrue(hazardFromTelltales(mapOf("TURN_SIGNAL_LEFT" to TelltaleLevel.BLINK, "TURN_SIGNAL_RIGHT" to TelltaleLevel.BLINK)))
        // Some clusters may report a flashing lamp as ON.
        assertTrue(hazardFromTelltales(mapOf("TURN_SIGNAL_LEFT" to TelltaleLevel.ON, "TURN_SIGNAL_RIGHT" to TelltaleLevel.ON)))
    }

    @Test
    fun oneTurnSignalIsNotHazard() {
        assertFalse(hazardFromTelltales(mapOf("TURN_SIGNAL_LEFT" to TelltaleLevel.BLINK)))
        assertFalse(hazardFromTelltales(mapOf("TURN_SIGNAL_RIGHT" to TelltaleLevel.BLINK, "ESC" to TelltaleLevel.BLINK)))
        assertFalse(hazardFromTelltales(emptyMap()))
    }
}
