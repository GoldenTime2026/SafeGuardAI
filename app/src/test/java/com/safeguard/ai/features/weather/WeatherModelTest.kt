package com.safeguard.ai.features.weather

import com.safeguard.ai.core.Caution
import com.safeguard.ai.core.TelltaleLevel
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.core.WiperLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherModelTest {

    private fun classify(s: VehicleSnapshot, now: Long = 0, wetUntil: Long = 0) = WeatherModel.classify(s, now, wetUntil)

    @Test
    fun `wiper level maps to rain intensity`() {
        assertEquals(RoadCondition.DRY, classify(VehicleSnapshot(wiper = WiperLevel.OFF)))
        assertEquals(RoadCondition.RAIN, classify(VehicleSnapshot(wiper = WiperLevel.INTERMITTENT)))
        assertEquals(RoadCondition.RAIN, classify(VehicleSnapshot(wiper = WiperLevel.LOW)))
        assertEquals(RoadCondition.HEAVY_RAIN, classify(VehicleSnapshot(wiper = WiperLevel.HIGH)))
        assertEquals(RoadCondition.UNKNOWN, classify(VehicleSnapshot()))
    }

    @Test
    fun `ice and fog outrank rain`() {
        val icy = VehicleSnapshot(wiper = WiperLevel.HIGH, telltales = mapOf("ICY_ROAD" to TelltaleLevel.ON))
        assertEquals(RoadCondition.ICE, classify(icy))
        assertEquals(RoadCondition.FOG, classify(VehicleSnapshot(wiper = WiperLevel.LOW, frontFogOn = true)))
        assertEquals(RoadCondition.FOG, classify(VehicleSnapshot(cautions = listOf(Caution("FOG_AREA", 800)))))
        assertEquals(RoadCondition.DRY, classify(VehicleSnapshot(wiper = WiperLevel.OFF, cautions = listOf(Caution("FOG_AREA", 3000)))))
    }

    @Test
    fun `road stays wet until the hold expires`() {
        val dry = VehicleSnapshot(wiper = WiperLevel.OFF)
        assertEquals(RoadCondition.WET, classify(dry, now = 1_000, wetUntil = 5_000))
        assertEquals(RoadCondition.DRY, classify(dry, now = 6_000, wetUntil = 5_000))
    }

    @Test
    fun `recommended speed applies the legal reduction`() {
        assertEquals(80, WeatherModel.recommendedSpeed(100, RoadCondition.RAIN))
        assertEquals(50, WeatherModel.recommendedSpeed(100, RoadCondition.HEAVY_RAIN))
        assertEquals(64, WeatherModel.recommendedSpeed(80, RoadCondition.WET))
        assertEquals(100, WeatherModel.recommendedSpeed(100, RoadCondition.DRY))
        assertNull(WeatherModel.recommendedSpeed(null, RoadCondition.RAIN))
        assertNull(WeatherModel.recommendedSpeed(0, RoadCondition.RAIN))
    }

    @Test
    fun `stopping distance grows on slippery roads`() {
        val dry = WeatherModel.stoppingDistanceMeters(80.0, RoadCondition.DRY)
        assertEquals(58.2, dry, 0.5) // 22.2 m reaction + 36.0 m braking
        assertTrue(WeatherModel.stoppingFactor(80.0, RoadCondition.RAIN) in 1.3..1.6)
        assertTrue(WeatherModel.stoppingFactor(80.0, RoadCondition.ICE) > 2.5)
        assertEquals(1.0, WeatherModel.stoppingFactor(80.0, RoadCondition.DRY), 1e-9)
    }

    @Test
    fun `wet hold is bounded and longer after heavy rain`() {
        assertEquals(5 * 60_000L, WeatherModel.wetHoldMillis(60_000, wasHeavy = false))
        assertEquals(20 * 60_000L, WeatherModel.wetHoldMillis(60 * 60_000L, wasHeavy = false))
        assertEquals(15 * 60_000L, WeatherModel.wetHoldMillis(10 * 60_000L, wasHeavy = true))
    }
}
