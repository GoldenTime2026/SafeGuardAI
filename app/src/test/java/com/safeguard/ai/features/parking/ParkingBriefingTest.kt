package com.safeguard.ai.features.parking

import com.safeguard.ai.core.Gear
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.core.WiperLevel
import com.safeguard.ai.risk.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingBriefingTest {

    private var alerts = setOf<String>()
    private val rule = ParkingBriefing(alerts = { alerts })
    private val driving = VehicleSnapshot(gear = Gear.DRIVE, speedKmh = 50.0, wiper = WiperLevel.OFF)
    private val parked = driving.copy(gear = Gear.PARK, speedKmh = 0.0)

    private fun driveThenPark(at: VehicleSnapshot = parked) = run {
        rule.evaluate(driving, 0)
        rule.evaluate(driving, 90_000)
        rule.evaluate(at, 100_000)
    }

    @Test
    fun `a rain warning here is said once when the car is parked`() {
        alerts = setOf("호우")
        val event = driveThenPark().single()
        assertEquals(RiskLevel.WEATHER, event.level)
        assertTrue(event.speech.contains("호우 재난문자가 나와 있어요"))
        assertTrue(event.speech.contains("지하주차장이나 하천 옆"))
        assertTrue(rule.evaluate(parked, 110_000).isEmpty())
    }

    @Test
    fun `nothing to say on a quiet night`() {
        assertTrue(driveThenPark().isEmpty())
    }

    @Test
    fun `a short move is not a parking`() {
        alerts = setOf("호우")
        rule.evaluate(driving, 0)
        assertTrue(rule.evaluate(parked, 20_000).isEmpty())
    }

    @Test
    fun `a cold night with a low EV battery asks to charge`() {
        val event = driveThenPark(parked.copy(outsideTempC = -12.0, evSocPercent = 22.0)).single()
        assertTrue(event.speech.contains("배터리가 22%라"))
        assertTrue(event.speech.contains("충전해 두세요"))
    }

    @Test
    fun `an open window in the rain`() {
        val event = driveThenPark(parked.copy(wiper = WiperLevel.LOW, openWindows = setOf("ROW_1_RIGHT"))).single()
        assertTrue(event.speech.contains("조수석 창문이 열려 있어요"))
    }

    @Test
    fun `open window labels and particles`() {
        assertEquals("선루프", ParkingBriefing.openLabel(setOf("ROOF_TOP_1")))
        assertEquals("가", ParkingBriefing.subjectParticle("선루프"))
        assertEquals("이", ParkingBriefing.subjectParticle("창문"))
        assertEquals("선루프와 창문", ParkingBriefing.openLabel(setOf("ROOF_TOP_1", "ROW_1_LEFT", "ROW_2_LEFT")))
        assertEquals("운전석 창문", ParkingBriefing.openLabel(setOf("ROW_1_LEFT")))
    }

    @Test
    fun `snow warning lifts the wipers`() {
        alerts = setOf("대설")
        assertTrue(driveThenPark().single().speech.contains("와이퍼를 세워 두고"))
    }
}
