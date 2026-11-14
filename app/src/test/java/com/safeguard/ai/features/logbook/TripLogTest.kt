package com.safeguard.ai.features.logbook

import com.safeguard.ai.core.Gear
import com.safeguard.ai.core.VehicleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TripLogTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val store = object : TripLogStore {
        var saved = emptyList<TripEntry>()
        override fun load() = saved
        override fun save(entries: List<TripEntry>) { saved = entries }
    }
    private var on = true
    private var where = "경남 창원시 의창구 중앙대로 151"
    private val log = TripLog(CoroutineScope(Dispatchers.Unconfined), store, enabled = { on }, address = { where }, zone = zone)

    private val start = LocalDateTime.of(2026, 11, 3, 9, 0).atZone(zone).toInstant().toEpochMilli()
    private val driving = VehicleSnapshot(gear = Gear.DRIVE, speedKmh = 60.0, odometerKm = 31_250.0)
    private val parked = VehicleSnapshot(gear = Gear.PARK, speedKmh = 0.0, odometerKm = 31_262.4)

    private fun drive() = run {
        log.evaluate(driving, start)
        where = "경남 김해시 김해대로 2401"
        log.evaluate(parked, start + 30 * 60_000)
    }

    @Test
    fun `a drive is logged by the odometer and the driver is asked about work`() {
        val ask = drive().single()
        assertTrue(ask.speech.contains("이번 운행 12킬로미터, 업무로 기록할까요?"))
        val entry = store.saved.single()
        assertEquals(12.4, entry.km, 0.01)
        assertEquals("경남 창원시 의창구 중앙대로 151", entry.from)
        assertEquals("경남 김해시 김해대로 2401", entry.to)
        assertEquals(TripPurpose.UNSET, entry.purpose)

        runBlocking { ask.confirm!!.onYes() }
        assertEquals(TripPurpose.BUSINESS, store.saved.single().purpose)
        runBlocking { ask.confirm!!.onNo() }
        assertEquals(TripPurpose.PERSONAL, store.saved.single().purpose)
    }

    @Test
    fun `nothing is recorded while switched off or for a few metres`() {
        on = false
        assertTrue(drive().isEmpty())
        on = true
        log.evaluate(driving, start)
        assertTrue(log.evaluate(driving.copy(gear = Gear.PARK, speedKmh = 0.0, odometerKm = 31_250.2), start + 60_000).isEmpty())
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `csv follows the tax form and the month summary counts business use`() {
        val ask = drive().single()
        runBlocking { ask.confirm!!.onYes() }
        val csv = log.csv(start).lines()
        assertTrue(csv[0].startsWith("사용일자(요일),사용자(성명),주행 전 계기판의 거리(km),주행 후 계기판의 거리(km)"))
        assertEquals(
            "\"2026-11-03(화)\",\"\",\"31250.0\",\"31262.4\",\"12.4\",\"0\",\"12.4\",\"경남 창원시 의창구 중앙대로 151 → 경남 김해시 김해대로 2401\"",
            csv[1].trimEnd('\r'),
        )
        val month = TripLog.summary(store.saved, start, zone)
        assertEquals(12.4, month.businessKm, 0.01)
        assertEquals(1.0, month.businessRatio, 0.001)
        // Another month has nothing.
        assertEquals(1, TripLog.csv(store.saved, start + 40L * 86_400_000, "", zone).lines().size)
    }
}
