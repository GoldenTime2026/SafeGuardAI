package com.safeguard.ai.features.share

import com.safeguard.ai.core.Gear
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.core.WiperLevel
import com.safeguard.ai.data.publicdata.Geo
import com.safeguard.ai.data.publicdata.HazardKind
import com.safeguard.ai.data.publicdata.HazardPoint
import com.safeguard.ai.data.publicdata.HazardSource
import com.safeguard.ai.features.hazard.HazardMap
import com.safeguard.ai.features.hazard.HazardRule
import com.safeguard.ai.features.weather.RoadCondition
import com.safeguard.ai.features.weather.WeatherStatus
import com.safeguard.ai.risk.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharingTest {

    private val lat = 35.249324
    private val lon = 128.698500

    private fun car(speed: Double, heading: Float = 90f) = VehicleSnapshot(
        gear = Gear.DRIVE, speedKmh = speed, latitude = lat, longitude = lon, headingDeg = heading,
    )

    @Test
    fun `braking hard at speed is reported once`() {
        val reports = mutableListOf<Pair<Double, Double>>()
        val detector = HardBrakingDetector { a, b, _ -> reports += a to b }
        detector.evaluate(car(100.0), 0L)
        detector.evaluate(car(90.0), 1_000L)
        detector.evaluate(car(65.0), 2_000L)
        assertEquals(1, reports.size)
        detector.evaluate(car(20.0), 3_000L)
        assertEquals(1, reports.size)
    }

    @Test
    fun `ordinary braking and slow traffic are not reported`() {
        val reports = mutableListOf<Unit>()
        val detector = HardBrakingDetector { _, _, _ -> reports += Unit }
        listOf(100.0, 95.0, 90.0, 85.0, 80.0).forEachIndexed { i, v -> detector.evaluate(car(v), i * 1_000L) }
        listOf(40.0, 10.0, 0.0).forEachIndexed { i, v -> detector.evaluate(car(v), 10_000L + i * 500L) }
        assertTrue(reports.isEmpty())
    }

    private fun shared(type: String, aheadMeters: Double, heading: Float, cars: Int = 1): HazardPoint {
        val (pLat, pLon) = Geo.offset(lat, lon, 90.0, aheadMeters)
        return HazardPoint("shared-$type-$heading", HazardKind.SHARED, HazardSource.SHARED, pLat, pLon, 50.0, type, cars, headingDeg = heading)
    }

    private fun rule(vararg points: HazardPoint) = HazardRule(HazardMap { 0L }.also { it.replaceShared(points.toList()) }, weather = { WeatherStatus(RoadCondition.DRY) })

    @Test
    fun `a crash shared by a car ahead on our side is announced at 1 km`() {
        val event = rule(shared("CRASH", 900.0, 90f)).evaluate(car(100.0), 0L).single()
        assertEquals(RiskLevel.FORWARD_HAZARD, event.level)
        assertTrue(event.speech, event.speech.endsWith("앞에 사고 차량이 있어요. 속도를 줄이세요."))
    }

    @Test
    fun `a crash on the other carriageway is not ours`() {
        assertTrue(rule(shared("CRASH", 900.0, 270f)).evaluate(car(100.0), 0L).isEmpty())
    }

    @Test
    fun `a jam tail names how many cars braked`() {
        val event = rule(shared("HARD_BRAKING", 700.0, 90f, cars = 3)).evaluate(car(100.0), 0L).single()
        assertTrue(event.speech, event.speech.contains("차량 3대가 급정거했어요"))
    }

    @Test
    fun `a near-miss spot is a hotspot and faded lanes are never read out`() {
        val event = rule(shared("NEAR_MISS", 700.0, 90f, cars = 4)).evaluate(car(100.0), 0L).single()
        assertEquals(RiskLevel.HOTSPOT, event.level)
        assertTrue(event.speech, event.speech.contains("충돌 경고가 잦았던 구간"))
        assertTrue(rule(shared("LANE_FADE", 700.0, 90f, cars = 4)).evaluate(car(100.0), 0L).isEmpty())
    }

    @Test
    fun `the 300 m reminder is a separate warning`() {
        val far = rule(shared("CRASH", 900.0, 90f)).evaluate(car(100.0), 0L).single()
        val near = rule(shared("CRASH", 250.0, 90f)).evaluate(car(100.0), 0L).single()
        assertTrue(near.key != far.key)
    }

    @Test
    fun `rain shared by cars ahead is said 3 km before, not once it rains here`() {
        val event = rule(shared("HEAVY_RAIN", 2_500.0, 90f, cars = 2)).evaluate(car(100.0), 0L).single()
        assertEquals(RiskLevel.WEATHER, event.level)
        assertTrue(event.speech, event.speech.contains("폭우가 내리고 있어요"))
        assertTrue(rule(shared("RAIN", 4_000.0, 90f, cars = 2)).evaluate(car(100.0), 0L).isEmpty())
        val raining = HazardRule(
            HazardMap { 0L }.also { it.replaceShared(listOf(shared("RAIN", 2_000.0, 90f, cars = 2))) },
            weather = { WeatherStatus(RoadCondition.RAIN) },
        )
        assertTrue(raining.evaluate(car(100.0), 0L).isEmpty())
    }

    @Test
    fun `wipers on while driving are shared as rain once, again only after a while`() {
        val shares = mutableListOf<String>()
        val rule = HazardRule(HazardMap { 0L }, weather = { WeatherStatus(RoadCondition.DRY) }, share = { t, _, _, _ -> shares += t })
        rule.evaluate(car(80.0).copy(wiper = WiperLevel.LOW), 0L)
        rule.evaluate(car(80.0).copy(wiper = WiperLevel.LOW), 60_000L)
        rule.evaluate(car(80.0).copy(wiper = WiperLevel.HIGH), 61_000L)
        rule.evaluate(car(80.0).copy(wiper = WiperLevel.HIGH), 7 * 60_000L)
        assertEquals(listOf("RAIN", "HEAVY_RAIN", "HEAVY_RAIN"), shares)
    }

    @Test
    fun `the poll asks for a grid cell, not the exact position`() {
        val (a, b) = SharingClient.cellCenter(35.249324, 128.698500)
        assertEquals(35.25, a, 1e-9)
        assertEquals(128.7, b, 1e-9)
    }

    @Test
    fun `our own report coming back from the server is not a warning`() {
        val mine = HazardReport("STOPPED_VEHICLE", lat, lon, 90f, at = 0L, source = HazardReport.Source.VEHICLE)
        val point = HazardPoint(
            id = "shared-1", kind = HazardKind.SHARED, source = HazardSource.SHARED,
            latitude = lat, longitude = lon, radiusMeters = 100.0, name = "STOPPED_VEHICLE",
        )
        assertTrue(SharingClient.isOwnEcho(point, listOf(mine), now = 60_000L))
        // Another type at the same spot, a stop 1 km away, or an hour later is someone else's.
        assertFalse(SharingClient.isOwnEcho(point.copy(name = "CRASH"), listOf(mine), now = 60_000L))
        val far = Geo.offset(lat, lon, 90.0, 1_000.0)
        assertFalse(SharingClient.isOwnEcho(point.copy(latitude = far.first, longitude = far.second), listOf(mine), now = 60_000L))
        assertFalse(SharingClient.isOwnEcho(point, listOf(mine), now = 61 * 60_000L))
    }

    @Test
    fun `disaster messages from the server keep their regions and original text`() {
        val json = """{"configured": true, "messages": [{"sn": 266640, "createdAt": 1786853132000,
            "regions": ["경상남도 김해시"], "step": "안전안내", "kind": "호우", "text": "13시부로 김해시 호우주의보 발효. [김해시]"}]}"""
        val m = SharingClient.parseDisasters(json).single()
        assertEquals(266640L, m.sn)
        assertEquals(listOf("경상남도 김해시"), m.regions)
        assertEquals("13시부로 김해시 호우주의보 발효. [김해시]", m.text)
        assertFalse(m.replay)
        assertTrue(SharingClient.parseDisasters("""{"configured": false, "messages": []}""").isEmpty())
    }
}
