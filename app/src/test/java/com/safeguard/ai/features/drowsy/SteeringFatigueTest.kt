package com.safeguard.ai.features.drowsy

import org.junit.Assert.assertEquals
import org.junit.Test

class SteeringFatigueTest {

    private var t = 0L

    /** An alert driver: a small correction left or right every second. */
    private fun SteeringFatigue.alert(seconds: Int) = repeat(seconds) {
        add(t, if (it % 2 == 0) 1f else -1f, 90.0)
        t += 1_000
    }

    /** A tired driver: wheel still for 9 s, then a sudden 6° jerk and back. */
    private fun SteeringFatigue.drowsy(seconds: Int) = repeat(seconds) {
        add(t, if (it % 10 == 9) 6f else 0f, 90.0)
        t += 1_000
    }

    @Test
    fun `an alert driver stays normal`() {
        val model = SteeringFatigue()
        model.alert(15 * 60)
        assertEquals(Fatigue.NORMAL, model.level)
    }

    @Test
    fun `few corrections and sudden jerks raise caution, then warning`() {
        val model = SteeringFatigue()
        model.alert(5 * 60 + 1)
        model.drowsy(61)
        assertEquals(Fatigue.CAUTION, model.level)
        model.drowsy(60)
        assertEquals(Fatigue.WARNING, model.level)
    }

    @Test
    fun `nothing is counted below cruising speed`() {
        val model = SteeringFatigue()
        model.alert(5 * 60 + 1)
        repeat(5 * 60) {
            model.add(t, if (it % 10 == 9) 6f else 0f, 30.0)
            t += 1_000
        }
        assertEquals(Fatigue.NORMAL, model.level)
    }

    @Test
    fun `reset forgets the baseline and the score`() {
        val model = SteeringFatigue()
        model.alert(5 * 60 + 1)
        model.drowsy(121)
        model.reset()
        assertEquals(Fatigue.NORMAL, model.level)
    }
}
