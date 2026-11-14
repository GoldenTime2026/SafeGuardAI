package com.safeguard.ai.features.hazard

import com.safeguard.ai.core.Caution
import com.safeguard.ai.core.Gear
import com.safeguard.ai.core.TrafficInfo
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.features.weather.CruiseCheck
import com.safeguard.ai.features.weather.RoadCondition
import com.safeguard.ai.features.weather.WeatherStatus
import com.safeguard.ai.risk.RiskLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class SchoolZoneRouteTest {

    private var avoided = 0
    private val rule = SchoolZoneRoute(avoidSchoolZones = { avoided++ }, zone = ZoneOffset.UTC)
    private val routing = TrafficInfo(routing = true, congestions = emptyList(), incidents = emptyList())

    private fun driving(minuteOfDay: String?, schoolZoneAt: Int?) = VehicleSnapshot(
        gear = Gear.DRIVE,
        speedKmh = 50.0,
        traffic = routing,
        minuteOfDayOverride = minuteOfDay?.split(":")?.let { (h, m) -> h.toInt() * 60 + m.toInt() },
        cautions = listOfNotNull(schoolZoneAt?.let { Caution("SCHOOL_ZONE", it) }),
    )

    @Test
    fun `offers a school-zone-free route once during commute hours`() {
        val event = rule.evaluate(driving("08:10", 1_200), 0L).single()
        assertEquals(RiskLevel.TRAFFIC, event.level)
        runBlocking { event.confirm!!.onYes() }
        assertEquals(1, avoided)
        assertTrue(rule.evaluate(driving("08:11", 1_000), 1_000L).isEmpty())
    }

    @Test
    fun `stays quiet outside school hours or when the zone is too close to avoid`() {
        assertTrue(rule.evaluate(driving("20:00", 1_200), 0L).isEmpty())
        assertTrue(rule.evaluate(driving("08:10", 200), 0L).isEmpty())
        assertTrue(rule.evaluate(driving("08:10", null), 0L).isEmpty())
    }

    @Test
    fun `no route, no offer`() {
        assertTrue(rule.evaluate(driving("08:10", 1_200).copy(traffic = null), 0L).isEmpty())
    }

    // ------------------------------------------------------------------ cruise check

    private var weather = WeatherStatus()
    private val cruise = CruiseCheck(weather = { weather })

    @Test
    fun `cruise set above 30 before a school zone is a forward hazard`() {
        val s = driving(null, 300).copy(cruiseActive = true, cruiseTargetKmh = 80.0)
        val event = cruise.evaluate(s, 0L).single()
        assertEquals(RiskLevel.FORWARD_HAZARD, event.level)
        assertTrue(event.speech.startsWith("크루즈가 80킬로미터예요."))
    }

    @Test
    fun `cruise above the rain speed is a weather warning, and off means nothing`() {
        weather = WeatherStatus(condition = RoadCondition.RAIN, recommendedKmh = 80)
        val s = driving(null, null).copy(cruiseActive = true, cruiseTargetKmh = 100.0)
        assertEquals(RiskLevel.WEATHER, cruise.evaluate(s, 0L).single().level)
        assertTrue(cruise.evaluate(s.copy(cruiseActive = false), 0L).isEmpty())
        assertTrue(cruise.evaluate(s.copy(cruiseTargetKmh = 84.0), 0L).isEmpty())
    }
}
