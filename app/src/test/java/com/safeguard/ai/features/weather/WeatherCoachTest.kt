package com.safeguard.ai.features.weather

import com.safeguard.ai.core.Caution
import com.safeguard.ai.core.Gear
import com.safeguard.ai.core.TelltaleLevel
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.core.WiperLevel
import com.safeguard.ai.risk.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherCoachTest {

    private val coach = WeatherCoach()
    private val driving = VehicleSnapshot(gear = Gear.DRIVE, speedKmh = 70.0, roadSpeedLimitKmh = 100, wiper = WiperLevel.OFF)

    @Test
    fun `rain onset announces the legal speed once`() {
        coach.evaluate(driving, 0)
        val events = coach.evaluate(driving.copy(wiper = WiperLevel.LOW), 1_000)
        val onset = events.single { it.key == "weather-onset-RAIN" }
        assertEquals(RiskLevel.WEATHER, onset.level)
        assertTrue(onset.speech.contains("80킬로미터"))
        assertTrue(coach.evaluate(driving.copy(wiper = WiperLevel.LOW), 2_000).none { it.key.startsWith("weather-onset") })
        assertEquals(80, coach.status.value.recommendedKmh)
    }

    @Test
    fun `rain at start is left to the briefing and announced once driving`() {
        val parked = driving.copy(gear = Gear.PARK, speedKmh = 0.0, wiper = WiperLevel.LOW)
        assertTrue(coach.evaluate(parked, 0).isEmpty())
        assertTrue(coach.evaluate(driving.copy(wiper = WiperLevel.LOW), 1_000).any { it.key == "weather-onset-RAIN" })
    }

    @Test
    fun `heavy rain after rain is announced and mentions defogging`() {
        coach.evaluate(driving.copy(wiper = WiperLevel.LOW), 0)
        val heavy = coach.evaluate(driving.copy(wiper = WiperLevel.HIGH), 1_000).single { it.key == "weather-onset-HEAVY_RAIN" }
        assertTrue(heavy.speech.contains("50킬로미터"))
        assertTrue(heavy.speech.contains("김서림"))
    }

    @Test
    fun `overspeed warns only after holding above the recommended speed`() {
        val fast = driving.copy(wiper = WiperLevel.HIGH, speedKmh = 90.0)
        coach.evaluate(fast, 0)
        assertTrue(coach.evaluate(fast, 2_000).none { it.key.startsWith("weather-overspeed") })
        assertTrue(coach.evaluate(fast, 3_500).any { it.key.startsWith("weather-overspeed") })
        coach.evaluate(fast.copy(speedKmh = 52.0), 4_000)
        assertTrue(coach.evaluate(fast, 5_000).none { it.key.startsWith("weather-overspeed") })
    }

    @Test
    fun `road stays wet after the wipers stop`() {
        coach.evaluate(driving.copy(wiper = WiperLevel.LOW), 0)
        coach.evaluate(driving.copy(wiper = WiperLevel.OFF), 60_000)
        assertEquals(RoadCondition.WET, coach.status.value.condition)
        assertEquals(80, coach.status.value.recommendedKmh)
        coach.evaluate(driving.copy(wiper = WiperLevel.OFF), 60_000 + 5 * 60_000 + 1)
        assertEquals(RoadCondition.DRY, coach.status.value.condition)
    }

    @Test
    fun `rainy school zone is a forward hazard`() {
        val events = coach.evaluate(driving.copy(wiper = WiperLevel.LOW, cautions = listOf(Caution("SCHOOL_ZONE", 250))), 0)
        assertEquals(RiskLevel.FORWARD_HAZARD, events.single { it.key == "school-zone" }.level)
    }

    @Test
    fun `overlaps fire only in adverse weather`() {
        val risky = driving.copy(drivingMode = "SPORT", washerFluidLow = true, telltales = mapOf("TPMS" to TelltaleLevel.ON))
        assertTrue(coach.evaluate(risky, 0).isEmpty())
        val keys = coach.evaluate(risky.copy(wiper = WiperLevel.LOW), 1_000).map { it.key }
        assertTrue(keys.containsAll(listOf("weather-sport-mode", "weather-washer-low", "weather-tpms")))
    }
}
