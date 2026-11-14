package com.safeguard.ai.features.weather

import com.safeguard.ai.core.Gear
import com.safeguard.ai.core.TelltaleColor
import com.safeguard.ai.core.TelltaleLevel
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.risk.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorBlockageTest {

    private var condition = RoadCondition.HEAVY_RAIN
    private val rule = SensorBlockage(weather = { WeatherStatus(condition) })
    private val driving = VehicleSnapshot(gear = Gear.DRIVE, speedKmh = 80.0)

    private fun lit(vararg lights: Pair<String, TelltaleColor>) = driving.copy(
        telltales = lights.associate { it.first to TelltaleLevel.ON },
        telltaleColors = lights.toMap(),
    )

    @Test
    fun `assist light turning yellow in heavy rain says the sensor is covered`() {
        val event = rule.evaluate(lit("FCA" to TelltaleColor.YELLOW, "LFA" to TelltaleColor.YELLOW), 0).single()
        assertEquals(RiskLevel.FORWARD_HAZARD, event.level)
        assertTrue(event.speech.startsWith("폭우로 전방 센서가 가려진 것 같아요."))
        assertTrue(event.speech.contains("전방 충돌방지 보조·차로 유지 보조 기능이 꺼졌어요"))
        assertTrue(rule.evaluate(lit("FCA" to TelltaleColor.YELLOW, "LFA" to TelltaleColor.YELLOW), 1_000).isEmpty())
    }

    @Test
    fun `assists coming back are announced once`() {
        rule.evaluate(lit("FCA" to TelltaleColor.YELLOW), 0)
        val back = rule.evaluate(lit("FCA" to TelltaleColor.WHITE), 1_000).single()
        assertEquals("sensor-restored", back.key)
        assertTrue(rule.evaluate(lit("FCA" to TelltaleColor.WHITE), 2_000).isEmpty())
    }

    @Test
    fun `rear sensors get rear advice`() {
        val event = rule.evaluate(lit("LCA_LEFT" to TelltaleColor.YELLOW, "LCA_RIGHT" to TelltaleColor.YELLOW), 0).single()
        assertTrue(event.speech.contains("후측방 센서가"))
        assertTrue(event.speech.contains("후측방 보조 기능이 꺼졌어요"))
        assertTrue(event.speech.contains("직접 돌아보세요"))
    }

    @Test
    fun `in dry weather an assist that stays off is a gentle check`() {
        condition = RoadCondition.DRY
        val off = lit("LKA" to TelltaleColor.YELLOW)
        assertTrue(rule.evaluate(off, 0).isEmpty())
        assertTrue(rule.evaluate(off, 20_000).isEmpty())
        val event = rule.evaluate(off, 31_000).single()
        assertEquals(RiskLevel.MAINTENANCE, event.level)
        assertTrue(event.speech.contains("직접 끄신 게 아니라면"))
    }

    @Test
    fun `nothing while parked`() {
        val parked = lit("FCA" to TelltaleColor.YELLOW).copy(gear = Gear.PARK, speedKmh = 0.0)
        assertTrue(rule.evaluate(parked, 0).isEmpty())
    }

    @Test
    fun `cold rain reads as snow or frost`() {
        assertEquals("눈이나 성에로", SensorBlockage.cause(driving.copy(outsideTempC = -3.0), RoadCondition.RAIN))
        assertEquals("비로", SensorBlockage.cause(driving.copy(outsideTempC = 5.0), RoadCondition.RAIN))
        assertEquals(null, SensorBlockage.cause(driving, RoadCondition.DRY))
    }
}
