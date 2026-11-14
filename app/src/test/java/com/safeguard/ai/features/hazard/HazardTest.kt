package com.safeguard.ai.features.hazard

import com.safeguard.ai.core.Gear
import com.safeguard.ai.core.TelltaleLevel
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.data.publicdata.Geo
import com.safeguard.ai.data.publicdata.HazardKind
import com.safeguard.ai.data.publicdata.HazardPoint
import com.safeguard.ai.data.publicdata.HazardSource
import com.safeguard.ai.features.briefing.DepartureBriefing
import com.safeguard.ai.features.weather.RoadCondition
import com.safeguard.ai.features.weather.WeatherStatus
import com.safeguard.ai.risk.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardTest {

    // A bridge with no weather station, and a point 400 m south of it heading north.
    private val bridgeLat = 35.1700
    private val bridgeLon = 128.5600
    private val south400 = Geo.offset(bridgeLat, bridgeLon, 180.0, 400.0)

    private fun approaching(extra: VehicleSnapshot.() -> VehicleSnapshot = { this }) = VehicleSnapshot(
        gear = Gear.DRIVE, speedKmh = 60.0, latitude = south400.first, longitude = south400.second, headingDeg = 0f,
    ).extra()

    private var weather = WeatherStatus(RoadCondition.DRY)

    @Test
    fun `three crowd events away from public data create a crowd point`() {
        val map = HazardMap { 0L }
        repeat(2) { map.report(CrowdEventKind.ICE, bridgeLat, bridgeLon, 0L) }
        assertTrue(map.active(0L).isEmpty())
        map.report(CrowdEventKind.ICE, bridgeLat, bridgeLon, 0L)
        val point = map.active(0L).single()
        assertEquals(HazardKind.CROWD_SLIP, point.kind)
        assertEquals(3, point.confirmations)
    }

    @Test
    fun `slip near a public ice point confirms it instead of creating a crowd point`() {
        val map = HazardMap { 0L }
        map.add(HazardPoint("ice-1", HazardKind.ICE_RISK, HazardSource.KEC_ICE_SECTION, bridgeLat, bridgeLon, 150.0, "교량"))
        map.report(CrowdEventKind.SLIP, bridgeLat, bridgeLon + 0.0005, 0L)
        val point = map.active(0L).single()
        assertEquals("ice-1", point.id)
        assertEquals(2, point.confirmations)
    }

    @Test
    fun `crowd points expire`() {
        val map = HazardMap { 0L }
        map.report(CrowdEventKind.HAZARD_LIGHTS, bridgeLat, bridgeLon, 0L)
        assertEquals(1, map.active(0L).size)
        assertTrue(map.active(HazardMap.HAZARD_TTL_MS + 1).isEmpty())
    }

    @Test
    fun `crowd point ahead is a forward hazard and behind is ignored`() {
        val map = HazardMap { 0L }
        repeat(3) { map.report(CrowdEventKind.ICE, bridgeLat, bridgeLon, 0L) }
        val rule = HazardRule(map, { weather })
        val event = rule.evaluate(approaching(), 0L).single()
        assertEquals(RiskLevel.FORWARD_HAZARD, event.level)
        assertTrue(event.speech.contains("결빙"))
        assertTrue(rule.evaluate(approaching { copy(headingDeg = 180f) }, 0L).isEmpty())
    }

    @Test
    fun `child hotspot speaks only at school hours and is promoted in rain`() {
        val map = HazardMap { 0L }
        // 150 m radius: the car 400 m away is 250 m from the edge, inside the 300 m trigger.
        map.add(HazardPoint("child-1", HazardKind.CHILD_HOTSPOT, HazardSource.TAAS_CHILD, bridgeLat, bridgeLon, 150.0, "초교 앞"))
        val rule = HazardRule(map, { weather })
        assertTrue(rule.evaluate(approaching { copy(minuteOfDayOverride = 11 * 60) }, 0L).isEmpty())
        assertEquals(RiskLevel.HOTSPOT, rule.evaluate(approaching { copy(minuteOfDayOverride = 8 * 60) }, 0L).single().level)
        weather = WeatherStatus(RoadCondition.RAIN, recommendedKmh = 24)
        val rainy = rule.evaluate(approaching { copy(minuteOfDayOverride = 8 * 60) }, 0L).single()
        assertEquals(RiskLevel.FORWARD_HAZARD, rainy.level)
        assertEquals(HazardRule.SCHOOL_ZONE_KEY, rainy.key)
        assertTrue(rainy.speech.contains("24킬로미터"))
    }

    @Test
    fun `ice risk needs freezing and holiday hotspot needs a holiday`() {
        val map = HazardMap { 0L }
        val ice = HazardPoint("ice-1", HazardKind.ICE_RISK, HazardSource.KEC_ICE_SECTION, bridgeLat, bridgeLon, 100.0, "교량")
        val holiday = ice.copy(id = "h-1", kind = HazardKind.HOLIDAY_HOTSPOT, source = HazardSource.TAAS_HOLIDAY)
        val rule = HazardRule(map, { weather })
        val dry = VehicleSnapshot()
        assertTrue(!rule.isActive(ice, dry.copy(outsideTempC = 8.0), RoadCondition.DRY, 0L))
        assertTrue(rule.isActive(ice, dry.copy(outsideTempC = -2.0), RoadCondition.DRY, 0L))
        assertTrue(rule.isActive(ice, dry.copy(telltales = mapOf("ICY_ROAD" to TelltaleLevel.ON)), RoadCondition.DRY, 0L))
        assertTrue(rule.isActive(holiday, dry.copy(holidayOverride = true), RoadCondition.DRY, 0L))
        assertTrue(!rule.isActive(holiday, dry.copy(holidayOverride = false), RoadCondition.DRY, 0L))
    }

    @Test
    fun `own hazard-light stop becomes a map point`() {
        val map = HazardMap { 0L }
        val rule = HazardRule(map, { weather })
        rule.evaluate(VehicleSnapshot(hazardOn = true, speedKmh = 0.0, latitude = bridgeLat, longitude = bridgeLon), 0L)
        assertEquals(HazardKind.CROWD_HAZARD_LIGHTS, map.active(0L).single().kind)
    }

    @Test
    fun `a car stopped with hazard lights is not warned about itself`() {
        val map = HazardMap { 0L }
        val rule = HazardRule(map, { weather })
        val stopped = VehicleSnapshot(hazardOn = true, speedKmh = 0.0, latitude = bridgeLat, longitude = bridgeLon)
        rule.evaluate(stopped, 0L)
        assertTrue(rule.evaluate(stopped, 1_000L).isEmpty())
    }

    @Test
    fun `spoken distance rounds naturally`() {
        assertEquals("300미터", HazardRule.spokenDistance(287.0))
        assertEquals("50미터", HazardRule.spokenDistance(5.0))
        assertEquals("1킬로미터", HazardRule.spokenDistance(980.0))
    }

    @Test
    fun `TAAS spot names keep the landmark for speech`() {
        with(com.safeguard.ai.data.publicdata.TaasHotspotAdapter) {
            assertEquals("반송중학교 부근", "경남 창원시 성산구 반림동(반송중학교 부근)".shortPlaceName())
            assertEquals("대동면", "경남 김해시 대동면".shortPlaceName())
        }
    }

    @Test
    fun `geojson polygon gives centre and radius`() {
        val circle = Geo.circleOf("""{"type":"Polygon","coordinates":[[[128.6802,35.2334],[128.6814,35.2334],[128.6814,35.2342],[128.6802,35.2342],[128.6802,35.2334]]]}""")
        assertNotNull(circle)
        assertEquals(35.2338, circle!!.lat, 0.0005)
        assertEquals(128.6808, circle.lon, 0.0005)
        assertTrue(circle.radiusM in 50.0..100.0)
    }

    @Test
    fun `briefing speaks once when parked and says so when nothing is wrong`() {
        val map = HazardMap { 0L }
        val briefing = DepartureBriefing(map, { WeatherStatus(RoadCondition.DRY) }, startedAt = 0L)
        val parked = VehicleSnapshot(gear = Gear.PARK, speedKmh = 0.0)
        assertTrue(briefing.evaluate(parked, 1_000L).isEmpty()) // still settling
        val first = briefing.evaluate(parked, 9_000L).single()
        assertEquals("오늘 경로는 특이사항이 없습니다.", first.speech)
        assertTrue(briefing.evaluate(parked, 20_000L).isEmpty())
    }

    @Test
    fun `briefing leads with weather and car checks and is skipped once driving`() {
        val map = HazardMap { 0L }
        val rainy = DepartureBriefing(map, { WeatherStatus(RoadCondition.RAIN) }, startedAt = 0L)
        val speech = rainy.evaluate(VehicleSnapshot(gear = Gear.PARK, washerFluidLow = true), 9_000L).single().speech
        assertTrue(speech.startsWith("지금 비 상태예요."))
        assertTrue(speech.contains("워셔액"))
        val late = DepartureBriefing(map, { WeatherStatus(RoadCondition.DRY) }, startedAt = 0L)
        assertTrue(late.evaluate(VehicleSnapshot(gear = Gear.DRIVE, speedKmh = 50.0), 9_000L).isEmpty())
        assertTrue(late.evaluate(VehicleSnapshot(gear = Gear.PARK), 20_000L).isEmpty())
    }
}
