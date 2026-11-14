package com.safeguard.ai.features.drowsy

import com.safeguard.ai.core.Gear
import com.safeguard.ai.core.TelltaleLevel
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.data.publicdata.Geo
import com.safeguard.ai.data.publicdata.Place
import com.safeguard.ai.risk.RiskLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrowsyCareTest {

    // 진례 졸음쉼터 (남해선) and a point 5 km west of it, heading east.
    private val rest = Place("진례", 35.249324, 128.758979)
    private val west5km = Geo.offset(rest.latitude, rest.longitude, 270.0, 5_000.0)

    private var awakened = 0
    private var waypoint: Place? = null
    private var napped = false
    private val care = DrowsyCare(
        restAreas = { listOf(rest) },
        awaken = { awakened++ },
        addWaypoint = { waypoint = it },
        nap = { napped = true },
    )

    private fun driving(minutes: Int? = null, minuteOfDay: Int = 14 * 60, telltale: Boolean = false) = VehicleSnapshot(
        gear = Gear.DRIVE, speedKmh = 90.0, latitude = west5km.first, longitude = west5km.second, headingDeg = 90f,
        drivingMinutes = minutes, minuteOfDayOverride = minuteOfDay,
        telltales = if (telltale) mapOf("DRIVER_ATTENTION" to TelltaleLevel.ON) else emptyMap(),
    )

    private fun parkedAtRest() = VehicleSnapshot(gear = Gear.PARK, speedKmh = 0.0, latitude = rest.latitude, longitude = rest.longitude)

    @Test
    fun `two hours of driving suggests the rest area ahead`() {
        val event = care.evaluate(driving(minutes = 125), 0L).single()
        assertEquals(RiskLevel.DROWSY, event.level)
        assertEquals("2시간 5분째 운전 중이에요. 5킬로미터 앞 진례 졸음쉼터에서 쉬어 가세요.", event.speech)
        assertEquals(1, care.stage)
    }

    @Test
    fun `the limit is shorter at night`() {
        assertTrue(care.evaluate(driving(minutes = 95), 0L).isEmpty())
        assertEquals(1, care.evaluate(driving(minutes = 95, minuteOfDay = 3 * 60), 1L).size)
    }

    @Test
    fun `the drowsiness telltale starts the wake-up program once`() {
        val event = care.evaluate(driving(telltale = true), 0L).single()
        assertTrue(event.speech.startsWith("졸음 경고가 켜졌어요."))
        assertEquals(1, awakened)
        assertTrue(care.evaluate(driving(telltale = true), 1_000L).isEmpty())
        assertEquals(1, awakened)
    }

    @Test
    fun `still drowsy after the wake-up program offers the rest area as a waypoint`() {
        care.evaluate(driving(telltale = true), 0L)
        assertTrue(care.evaluate(driving(telltale = true), DrowsyCare.ESCALATE_MS - 1).isEmpty())
        val offer = care.evaluate(driving(telltale = true), DrowsyCare.ESCALATE_MS).single()
        assertEquals("졸음이 계속되고 있어요. 5킬로미터 앞 진례 졸음쉼터를 경유지로 넣을까요?", offer.speech)
        runBlocking { offer.confirm!!.onYes() }
        assertEquals(rest, waypoint)
    }

    @Test
    fun `parking at a rest area after a warning offers a nap`() {
        care.evaluate(driving(minutes = 130), 0L)
        val offer = care.evaluate(parkedAtRest(), 1_000L).single()
        assertTrue(offer.speech.startsWith("15분 쉬어 가실래요?"))
        runBlocking { offer.confirm!!.onYes() }
        assertTrue(napped)
        assertTrue(care.evaluate(parkedAtRest(), 2_000L).isEmpty())
    }

    @Test
    fun `no nap offer without a drowsiness episode`() {
        assertTrue(care.evaluate(parkedAtRest(), 0L).isEmpty())
    }

    @Test
    fun `saying 나 졸려 wakes the driver up and then offers the rest area`() {
        care.driverSaysSleepy()
        val wake = care.evaluate(driving(), 0L).single()
        assertTrue(wake.speech.startsWith("졸리시군요."))
        assertEquals(1, awakened)
        val offer = care.evaluate(driving(), 1_000L).single()
        assertTrue(offer.speech, offer.speech.startsWith("쉬어 가는 게 가장 좋아요.") && offer.speech.contains("진례 졸음쉼터를 경유지로 넣을까요?"))
    }

    @Test
    fun `saying 나 졸려 while parked offers a nap`() {
        care.driverSaysSleepy()
        val offer = care.evaluate(parkedAtRest(), 0L).single()
        assertTrue(offer.speech.startsWith("15분 쉬어 가실래요?"))
    }

    @Test
    fun `driving time is spoken naturally`() {
        assertEquals("2시간", DrowsyCare.drivingTime(120))
        assertEquals("45분", DrowsyCare.drivingTime(45))
        assertEquals("1시간 30분", DrowsyCare.drivingTime(90))
    }
}
